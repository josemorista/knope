(ns knope.controllers.sync-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [knope.controllers.sync :as controllers.sync]
            [knope.controllers.task :as controllers.task]
            [knope.diplomat.git :as git]
            [knope.diplomat.sync-state :as sync-state]
            [knope.helpers :as helpers]
            [pod.babashka.go-sqlite3 :as sqlite]))

(use-fixtures :each helpers/with-components)

(defn- sh-git [& args]
  (let [{:keys [exit err out]} (apply process/shell {:out :string :err :string :continue true}
                                      "git" "-c" "commit.gpgsign=false" args)]
    (when-not (zero? exit) (throw (ex-info err {:args args})))
    (str/trim out)))

(defn- tmp [& parts] (str (apply fs/path (fs/parent helpers/*db-path*) parts)))

(defn- create-remote! [name]
  (let [bare (tmp name)
        work (tmp (str name "-seed"))]
    (sh-git "init" "--bare" "--initial-branch=main" bare)
    (sh-git "clone" "--quiet" bare work)
    (sh-git "-C" work "checkout" "-B" "main")
    (spit (tmp (str name "-seed") "README.md") "placeholder\n")
    (sh-git "-C" work "add" "README.md")
    (sh-git "-C" work "-c" "user.name=seed" "-c" "user.email=seed@localhost" "commit" "--quiet" "-m" "seed")
    (sh-git "-C" work "push" "--quiet" "origin" "HEAD:main")
    bare))

(defn- state []
  (edn/read-string (slurp (str helpers/*db-path* ".sync/state.edn"))))

(deftest remote-set-needs-a-url
  (is (= :validation/bad-remote (:error/code (controllers.sync/remote-set! nil helpers/*components*))))
  (is (= :validation/bad-remote (:error/code (controllers.sync/remote-set! " " helpers/*components*)))))

(deftest remote-set-to-the-same-url-keeps-the-sync-state
  (let [url (create-remote! "a.git")]
    (controllers.sync/remote-set! url helpers/*components*)
    (spit (str helpers/*db-path* ".sync/state.edn") (pr-str {:remote url :commit "c1" :fingerprint "f1"}))
    (controllers.sync/remote-set! url helpers/*components*)
    (is (= {:remote url :commit "c1" :fingerprint "f1"} (state)))))

(deftest remote-set-to-another-url-starts-over
  (let [a (create-remote! "a.git")
        b (create-remote! "b.git")]
    (controllers.sync/remote-set! a helpers/*components*)
    (spit (str helpers/*db-path* ".sync/state.edn") (pr-str {:remote a :commit "c1" :fingerprint "f1"}))
    (fs/create-dirs (str helpers/*db-path* ".sync/clone"))
    (controllers.sync/remote-set! b helpers/*components*)
    (is (= {:remote b} (state)))
    (is (not (fs/exists? (str helpers/*db-path* ".sync/clone"))))))

(deftest first-push-with-mismatched-passphrases-pushes-nothing
  (let [url  (create-remote! "a.git")
        head (sh-git "-C" url "rev-parse" "HEAD")
        [reader] (helpers/scripted-reader ["one" "two"])]
    (controllers.sync/remote-set! url helpers/*components*)
    (is (= :sync/passphrase-mismatch
           (:error/code (controllers.sync/push! false (assoc helpers/*components* :read-passphrase reader)))))
    (is (= head (sh-git "-C" url "rev-parse" "HEAD")))))

(deftest a-push-from-a-stale-clone-is-reported-as-remote-changed
  (let [url   (create-remote! "a.git")
        clone (tmp "clone")]
    (sh-git "clone" "--quiet" url clone)
    (spit (tmp "a.git-seed" "README.md") "moved on\n")
    (sh-git "-C" (tmp "a.git-seed") "-c" "user.name=seed" "-c" "user.email=seed@localhost"
            "commit" "--quiet" "-am" "moved on")
    (sh-git "-C" (tmp "a.git-seed") "push" "--quiet" "origin" "HEAD:main")
    (spit (str (fs/path clone "tasks.knope")) "x")
    (git/commit-file clone "tasks.knope")
    (is (= {:error/code :sync/remote-changed} (git/push clone)))))

(deftest a-git-error-carries-git-stderr
  (let [missing (tmp "missing.git")
        result  (git/ls-remote missing)]
    (is (= :sync/git-failed (:error/code result)))
    (is (str/includes? (get-in result [:error/details :err]) missing))))

(defn- with-reader [components answers]
  (assoc components :read-passphrase (first (helpers/scripted-reader answers))))

(deftest pull-refuses-when-the-database-changes-while-it-waits-for-the-passphrase
  (let [url   (create-remote! "a.git")
        other (helpers/test-components (tmp "other" "tasks.db"))]
    (controllers.sync/remote-set! url helpers/*components*)
    (controllers.task/add! {:title "from a"} helpers/*components*)
    (is (= {:outcome :pushed}
           (controllers.sync/push! false (with-reader helpers/*components* ["p" "p"]))))
    (controllers.sync/remote-set! url other)
    (let [reader (fn [_]
                   (controllers.task/add! {:title "typed meanwhile"} other)
                   "p")
          result (controllers.sync/pull! false (assoc other :read-passphrase reader))]
      (is (= {:error/code :sync/local-changed} result))
      (is (= ["typed meanwhile"]
             (mapv :title (sqlite/query (:sqlite other) ["SELECT title FROM tasks ORDER BY id"])))))))

(deftest pull-records-the-fingerprint-of-the-database-it-wrote
  (let [url   (create-remote! "a.git")
        other (helpers/test-components (tmp "other" "tasks.db"))]
    (controllers.sync/remote-set! url helpers/*components*)
    (controllers.task/add! {:title "from a"} helpers/*components*)
    (controllers.sync/push! false (with-reader helpers/*components* ["p" "p"]))
    (controllers.sync/remote-set! url other)
    (is (= {:outcome :pulled} (controllers.sync/pull! false (with-reader other ["p"]))))
    (is (= (sync-state/fingerprint (:sqlite other))
           (:fingerprint (edn/read-string (slurp (str (:sqlite other) ".sync/state.edn"))))))))

(defn- create-remote-with-linked-file!
  [name target]
  (let [bare (create-remote! name)
        work (tmp (str name "-seed"))]
    (fs/create-sym-link (fs/path work "tasks.knope") target)
    (sh-git "-C" work "add" "tasks.knope")
    (sh-git "-C" work "-c" "user.name=seed" "-c" "user.email=seed@localhost" "commit" "--quiet" "-m" "link")
    (sh-git "-C" work "push" "--quiet" "origin" "HEAD:main")
    bare))

(deftest push-refuses-a-remote-file-that-is-a-symbolic-link
  (let [target (tmp "outside.txt")
        url    (create-remote-with-linked-file! "a.git" target)
        head   (sh-git "-C" url "rev-parse" "HEAD")]
    (controllers.sync/remote-set! url helpers/*components*)
    (is (= :sync/remote-file-link
           (:error/code (controllers.sync/push! false (with-reader helpers/*components* ["p" "p"])))))
    (is (not (fs/exists? target)))
    (is (= head (sh-git "-C" url "rev-parse" "HEAD")))))

(deftest pull-refuses-a-remote-file-that-is-a-symbolic-link
  (let [target (tmp "outside.txt")
        url    (create-remote-with-linked-file! "a.git" target)
        before (sync-state/fingerprint helpers/*db-path*)]
    (spit target "keep me")
    (controllers.sync/remote-set! url helpers/*components*)
    (is (= :sync/remote-file-link
           (:error/code (controllers.sync/pull! true (with-reader helpers/*components* ["p"])))))
    (is (= "keep me" (slurp target)))
    (is (= before (sync-state/fingerprint helpers/*db-path*)))))
