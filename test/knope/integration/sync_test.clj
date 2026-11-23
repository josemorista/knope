(ns knope.integration.sync-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [knope.adapters.crypto :as crypto]
            [knope.diplomat.cli :as cli]
            [knope.helpers :as helpers]
            [pod.babashka.go-sqlite3 :as sqlite])
  (:import (java.security MessageDigest)
           (java.util Arrays HexFormat)))

;; The remote is a local bare git repository seeded with a placeholder file, standing in for a gist.

(def ^:dynamic *gist* nil)
(def ^:dynamic *other-db* nil)
(def ^:dynamic *other* nil)

(def passphrase "correct horse battery staple")

(defn- git [& args]
  (let [{:keys [exit out err]} (apply process/shell
                                      {:out :string :err :string :continue true}
                                      "git" "-c" "commit.gpgsign=false" args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git failed: " err) {:args args})))
    (str/trim out)))

(defn- create-gist! [dir]
  (let [bare (str (fs/path dir "gist.git"))
        work (str (fs/path dir "gist-seed"))]
    (git "init" "--bare" "--initial-branch=main" bare)
    (git "clone" "--quiet" bare work)
    (git "-C" work "checkout" "-B" "main")
    (spit (str (fs/path work "README.md")) "placeholder\n")
    (git "-C" work "add" "README.md")
    (git "-C" work "-c" "user.name=seed" "-c" "user.email=seed@localhost" "commit" "--quiet" "-m" "seed")
    (git "-C" work "push" "--quiet" "origin" "HEAD:main")
    bare))

(defn- with-gist-and-second-machine [f]
  (let [dir (fs/parent helpers/*db-path*)
        db  (str (fs/path dir "other" "tasks.db"))]
    (binding [*gist*     (create-gist! dir)
              *other-db* db
              *other*    (helpers/test-components db)]
      (f))))

(use-fixtures :each helpers/with-components with-gist-and-second-machine)

(defn- run
  "The CLI result plus :asked, the prompts the passphrase reader saw."
  [components answers & argv]
  (let [[reader asked] (helpers/scripted-reader answers)
        result         (cli/run (assoc components :read-passphrase reader) {:color? false} (vec argv))]
    (assoc result :asked @asked)))

(defn- run-a [answers & argv] (apply run helpers/*components* answers argv))
(defn- run-b [answers & argv] (apply run *other* answers argv))

(defn- ok! [result]
  (is (= 0 (:exit result)) (str "setup step failed: " (:err result)))
  result)

(defn- add-task! [run-machine title]
  (ok! (run-machine [] "add" title)))

(defn- set-remote! [run-machine]
  (ok! (run-machine [] "remote" "set" *gist*)))

(defn- titles [db-path]
  (mapv :title (sqlite/query db-path ["SELECT title FROM tasks ORDER BY id"])))

(defn- file-bytes [path] (fs/read-all-bytes path))

(defn- unchanged? [^bytes before path]
  (Arrays/equals before ^bytes (file-bytes path)))

(defn- sha256-hex [path]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (file-bytes path))))

(defn- state [db-path]
  (edn/read-string (slurp (str db-path ".sync/state.edn"))))

(defn- gist-head [] (git "-C" *gist* "rev-parse" "HEAD"))

(defn- gist-commits [] (str/split-lines (git "-C" *gist* "rev-list" "HEAD")))

(defn- gist-files [] (set (str/split-lines (git "-C" *gist* "ls-tree" "--name-only" "HEAD"))))

(defn- gist-knope-file []
  (:out (process/shell {:out :bytes} "git" "-C" *gist* "show" "HEAD:tasks.knope")))

(defn- titles-in-gist
  []
  (let [plain (crypto/decrypt (gist-knope-file) passphrase helpers/test-kdf-iterations)
        path  (str (fs/path (fs/parent helpers/*db-path*) "gist-copy.db"))]
    (fs/write-bytes path plain)
    (titles path)))

(defn- mentions? [text & fragments]
  (let [text (str/lower-case text)]
    (every? #(str/includes? text (str/lower-case %)) fragments)))

(defn- folder-names [db-path]
  (set (map (comp str fs/file-name) (fs/list-dir (fs/parent db-path)))))

(defn- machine-a-pushed!
  [& task-titles]
  (doseq [t task-titles] (add-task! run-a t))
  (set-remote! run-a)
  (ok! (run-a [passphrase passphrase] "push")))

(defn- both-machines-synced!
  []
  (machine-a-pushed! "write report" "call Ron")
  (set-remote! run-b)
  (ok! (run-b [passphrase] "pull")))

(deftest first-push-uploads-the-encrypted-database-to-an-empty-gist
  (add-task! run-a "write report")
  (add-task! run-a "call Ron")
  (set-remote! run-a)
  (let [seed-head (gist-head)
        result    (run-a [passphrase passphrase] "push")]
    (is (= 0 (:exit result)) (:err result))
    (is (= 2 (count (:asked result))) "the passphrase is typed twice")
    (is (not= seed-head (gist-head)))
    (is (some #{seed-head} (gist-commits)) "the placeholder revision is still in the history")
    (is (= #{"README.md" "tasks.knope"} (gist-files)) "the placeholder file is left alone")
    (is (= "KNOPE" (String. ^bytes (byte-array (take 5 (gist-knope-file))) "UTF-8")))
    (is (= ["write report" "call Ron"] (titles-in-gist)))
    (is (= (gist-head) (:commit (state helpers/*db-path*))))))

(deftest push-is-refused-when-another-machine-pushed-first
  (both-machines-synced!)
  (add-task! run-a "from machine A")
  (ok! (run-a [passphrase] "push"))
  (add-task! run-b "from machine B")
  (let [head   (gist-head)
        result (run-b [passphrase] "push")]
    (is (= 1 (:exit result)))
    (is (mentions? (:err result) "knope pull"))
    (is (= head (gist-head)))
    (is (empty? (:asked result)) "no passphrase is asked for a refused push")))

(deftest first-push-to-a-gist-that-already-holds-a-knope-file-is-refused
  (machine-a-pushed! "write report")
  (add-task! run-b "mine")
  (set-remote! run-b)
  (let [head   (gist-head)
        result (run-b [passphrase passphrase] "push")]
    (is (= 1 (:exit result)))
    (is (mentions? (:err result) "knope pull" "--force"))
    (is (= head (gist-head)))
    (is (empty? (:asked result)))))

(deftest a-forced-push-keeps-the-local-side-as-a-new-gist-revision
  (both-machines-synced!)
  (add-task! run-a "from machine A")
  (ok! (run-a [passphrase] "push"))
  (add-task! run-b "from machine B")
  (let [head-before (gist-head)
        commits     (gist-commits)
        result      (run-b [passphrase] "push" "--force")]
    (is (= 0 (:exit result)) (:err result))
    (is (not= head-before (gist-head)))
    (is (= (inc (count commits)) (count (gist-commits))) "one revision was added")
    (is (every? (set (gist-commits)) commits) "the earlier revisions are kept")
    (is (= (titles *other-db*) (titles-in-gist)) "the latest revision is the local database")
    (is (= ["write report" "call Ron" "from machine B"] (titles-in-gist)))))

(deftest pull-replaces-an-unchanged-local-database-with-the-remote-one
  (both-machines-synced!)
  (add-task! run-a "from machine A")
  (ok! (run-a [passphrase] "push"))
  (let [result (run-b [passphrase] "pull")]
    (is (= 0 (:exit result)) (:err result))
    (is (= ["write report" "call Ron" "from machine A"] (titles *other-db*)))
    (is (= (gist-head) (:commit (state *other-db*))))
    (is (= (sha256-hex *other-db*) (:fingerprint (state *other-db*))))))

(deftest pull-is-refused-when-the-local-database-has-unpushed-changes
  (both-machines-synced!)
  (add-task! run-a "from machine A")
  (ok! (run-a [passphrase] "push"))
  (add-task! run-b "from machine B")
  (let [before (file-bytes *other-db*)
        result (run-b [passphrase] "pull")]
    (is (= 1 (:exit result)))
    (is (mentions? (:err result) "local changes" "lost"))
    (is (unchanged? before *other-db*))
    (is (empty? (:asked result)) "no passphrase is asked for a refused pull")))

(deftest a-forced-pull-keeps-the-remote-side
  (both-machines-synced!)
  (add-task! run-a "from machine A")
  (ok! (run-a [passphrase] "push"))
  (add-task! run-b "from machine B")
  (let [result (run-b [passphrase] "pull" "--force")]
    (is (= 0 (:exit result)) (:err result))
    (is (= ["write report" "call Ron" "from machine A"] (titles *other-db*)))
    (is (= ["write report" "call Ron" "from machine B"]
           (titles (str *other-db* ".sync/before-pull.db")))
        "the replaced database is kept next to the sync state")))

(deftest a-wrong-passphrase-on-pull-leaves-the-local-database-untouched
  (both-machines-synced!)
  (add-task! run-a "from machine A")
  (ok! (run-a [passphrase] "push"))
  (let [before (file-bytes *other-db*)
        names  (folder-names *other-db*)
        result (run-b ["not the passphrase"] "pull")]
    (is (= 1 (:exit result)))
    (is (mentions? (:err result) "passphrase" "corrupted"))
    (is (unchanged? before *other-db*))
    (is (= names (folder-names *other-db*)) "no temporary file is left behind")))

(deftest first-pull-on-a-machine-with-no-tasks-takes-the-remote-database
  (machine-a-pushed! "write report" "call Ron")
  (set-remote! run-b)
  (let [result (run-b [passphrase] "pull")]
    (is (= 0 (:exit result)) (:err result))
    (is (= ["write report" "call Ron"] (titles *other-db*)))
    (is (= (gist-head) (:commit (state *other-db*))))))

(deftest first-pull-on-a-machine-with-existing-tasks-is-refused
  (machine-a-pushed! "write report")
  (add-task! run-b "mine")
  (set-remote! run-b)
  (let [before (file-bytes *other-db*)
        result (run-b [passphrase] "pull")]
    (is (= 1 (:exit result)))
    (is (mentions? (:err result) "tasks" "lost"))
    (is (unchanged? before *other-db*))
    (is (empty? (:asked result)))))

(deftest first-pull-counts-closed-tasks-as-existing-tasks
  (machine-a-pushed! "write report")
  (add-task! run-b "mine")
  (ok! (run-b [] "done" "1"))
  (set-remote! run-b)
  (let [before (file-bytes *other-db*)
        result (run-b [passphrase] "pull")]
    (is (= 1 (:exit result)))
    (is (unchanged? before *other-db*))))

(deftest sync-with-nothing-to-do-asks-for-no-passphrase
  (both-machines-synced!)
  (testing "push on the machine that pushed"
    (let [result (run-a [] "push")]
      (is (= 0 (:exit result)) (:err result))
      (is (helpers/said? (:out result) :sync/in-sync {}))
      (is (empty? (:asked result)))))
  (testing "pull on the machine that pulled"
    (let [result (run-b [] "pull")]
      (is (= 0 (:exit result)) (:err result))
      (is (helpers/said? (:out result) :sync/in-sync {}))
      (is (empty? (:asked result)))))
  (testing "pull on the machine that pushed"
    (let [result (run-a [] "pull")]
      (is (= 0 (:exit result)) (:err result))
      (is (helpers/said? (:out result) :sync/in-sync {}))
      (is (empty? (:asked result))))))

(deftest pull-from-a-gist-with-no-knope-file-says-there-is-nothing-to-pull
  (add-task! run-a "write report")
  (set-remote! run-a)
  (let [before (file-bytes helpers/*db-path*)
        head   (gist-head)
        result (run-a [passphrase] "pull")]
    (is (= 1 (:exit result)))
    (is (mentions? (:err result) "nothing to pull"))
    (is (unchanged? before helpers/*db-path*))
    (is (= head (gist-head)))))

(deftest sync-without-a-remote-explains-how-to-set-one
  (add-task! run-a "write report")
  (let [before (file-bytes helpers/*db-path*)]
    (doseq [command ["push" "pull"]]
      (testing command
        (let [result (run-a [passphrase passphrase] command)]
          (is (= 1 (:exit result)))
          (is (mentions? (:err result) "knope remote set"))
          (is (unchanged? before helpers/*db-path*)))))))

(deftest git-failing-on-first-contact-shows-its-error-with-an-access-hint
  (add-task! run-a "write report")
  (set-remote! run-a)
  (fs/delete-tree *gist*)
  (let [before (file-bytes helpers/*db-path*)]
    (doseq [command ["push" "pull"]]
      (testing command
        (let [result (run-a [passphrase passphrase] command)]
          (is (= 1 (:exit result)))
          (is (str/includes? (:err result) *gist*) "git's own error output is shown")
          (is (re-find #"(?i)ssh|setup-git" (:err result)) "the access hint is shown")
          (is (unchanged? before helpers/*db-path*)))))))

(deftest git-failing-after-an-earlier-sync-shows-its-error-with-an-access-hint
  (both-machines-synced!)
  (add-task! run-a "from machine A")
  (fs/delete-tree *gist*)
  (let [before (file-bytes helpers/*db-path*)]
    (doseq [command ["push" "pull"]]
      (testing command
        (let [result (run-a [passphrase] command)]
          (is (= 1 (:exit result)))
          (is (str/includes? (:err result) *gist*))
          (is (re-find #"(?i)ssh|setup-git" (:err result)))
          (is (unchanged? before helpers/*db-path*)))))))

(deftest first-push-without-a-terminal-says-sync-needs-a-terminal
  (add-task! run-a "write report")
  (set-remote! run-a)
  (let [before (file-bytes helpers/*db-path*)
        head   (gist-head)
        result (run-a [] "push")]
    (is (= 1 (:exit result)))
    (is (mentions? (:err result) "terminal"))
    (is (unchanged? before helpers/*db-path*))
    (is (= head (gist-head)))))

(deftest pull-without-a-terminal-says-sync-needs-a-terminal
  (machine-a-pushed! "write report")
  (set-remote! run-b)
  (let [before (file-bytes *other-db*)
        head   (gist-head)
        result (run-b [] "pull")]
    (is (= 1 (:exit result)))
    (is (mentions? (:err result) "terminal"))
    (is (unchanged? before *other-db*))
    (is (= head (gist-head)))))

(deftest push-is-refused-while-a-journal-sits-next-to-the-database
  (add-task! run-a "write report")
  (set-remote! run-a)
  (let [journal (str helpers/*db-path* "-journal")
        _       (spit journal "")
        before  (file-bytes helpers/*db-path*)
        head    (gist-head)
        result  (run-a [passphrase passphrase] "push")]
    (is (= 1 (:exit result)))
    (is (str/includes? (:err result) journal) "the journal's path is named")
    (is (mentions? (:err result) "try again" "delete"))
    (is (unchanged? before helpers/*db-path*))
    (is (= head (gist-head)))
    (is (fs/exists? journal) "knope leaves the journal where it is")
    (is (empty? (:asked result)))))

(deftest pull-is-refused-while-a-journal-sits-next-to-the-database
  (machine-a-pushed! "write report")
  (set-remote! run-b)
  (let [journal (str *other-db* "-journal")
        _       (spit journal "")
        before  (file-bytes *other-db*)
        head    (gist-head)
        result  (run-b [passphrase] "pull")]
    (is (= 1 (:exit result)))
    (is (str/includes? (:err result) journal))
    (is (mentions? (:err result) "try again" "delete"))
    (is (unchanged? before *other-db*))
    (is (= head (gist-head)))
    (is (fs/exists? journal))
    (is (empty? (:asked result)))))
