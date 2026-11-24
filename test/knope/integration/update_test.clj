(ns knope.integration.update-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [knope.diplomat.cli :as cli]
            [knope.helpers :as helpers]))

(use-fixtures :each helpers/with-components)

(defn- with-jar
  "The test components with the :jar functions replaced by fakes."
  [running version]
  (assoc helpers/*components*
         :jar {:running (constantly running)
               :version (constantly version)}))

(defn- run [components & argv]
  (cli/run components {:color? false} (vec argv)))

(deftest version-of-a-versioned-jar-prints-knope-and-the-version
  (is (= {:exit 0 :out "knope 1.2.3\n" :err ""}
         (run (with-jar {:path "/opt/knope/knope.jar"} "1.2.3") "--version"))))

(deftest version-of-an-unversioned-jar-says-unversioned
  (is (= {:exit 0 :out "knope (unversioned)\n" :err ""}
         (run (with-jar {:path "/opt/knope/knope.jar"} nil) "--version"))))

(deftest version-from-a-checkout-says-dev
  (is (= {:exit 0 :out "knope dev\n" :err ""}
         (run (with-jar nil nil) "--version"))))

(deftest version-needs-no-database-in-the-components
  (testing "components built the way --version builds them, with no :sqlite key"
    (let [lite (-> (select-keys helpers/*components* [:clock])
                   (assoc :jar {:running (constantly {:path "/opt/knope/knope.jar"})
                                :version (constantly "1.2.3")}))]
      (is (= {:exit 0 :out "knope 1.2.3\n" :err ""}
             (run lite "--version"))))))

(deftest usage-lists-the-version-flag
  (let [{:keys [exit out err]} (run helpers/*components* "--help")]
    (is (= 0 exit))
    (is (= "" err))
    (is (str/includes? out "--version"))))

(deftest version-after-another-command-is-still-an-unknown-option
  (is (= {:exit 1 :out "" :err "Unknown option '--version'.
That's not in any of my binders.
"}
         (run (with-jar {:path "/opt/knope/knope.jar"} "1.2.3") "tasks" "list" "--version"))))

(defn- with-update
  "The test components with fake :release and :jar functions. The fake jar
  is at /opt/knope/knope.jar. Every download and replace lands in calls."
  [calls {:keys [version latest-tag temp-beside! download! replace! running]
          :or   {version      "1.0.0"
                 latest-tag   {:ok "v1.1.0"}
                 temp-beside! "/opt/knope/knope-1.jar.tmp"
                 running      {:path "/opt/knope/knope.jar"}}}]
  (assoc helpers/*components*
         :release {:latest-tag (fn [] (swap! calls conj [:latest-tag]) latest-tag)
                   :download!  (fn [& args] (swap! calls conj (into [:download!] args)) download!)}
         :jar     {:running      (constantly running)
                   :version      (constantly version)
                   :temp-beside! (fn [& args] (swap! calls conj (into [:temp-beside!] args)) temp-beside!)
                   :replace!     (fn [& args] (swap! calls conj (into [:replace!] args)) replace!)
                   :discard!     (fn [& args] (swap! calls conj (into [:discard!] args)) nil)}))

(deftest update-replaces-an-older-jar-and-names-both-versions
  (let [calls (atom [])]
    (let [result (run (with-update calls {}) "update")]
      (is (= 0 (:exit result)))
      (is (= "" (:err result)))
      (is (helpers/said? (:out result) :update/updated {:from "1.0.0" :to "1.1.0"}) (:out result)))
    (is (= 1 (count (filter #(= :replace! (first %)) @calls))))))

(deftest update-replaces-an-unversioned-jar
  (let [result (run (with-update (atom []) {:version nil}) "update")]
    (is (= 0 (:exit result)))
    (is (= "" (:err result)))
    (is (helpers/said? (:out result) :update/updated {:from nil :to "1.1.0"}) (:out result))))

(deftest update-says-already-on-the-version-when-it-is-the-latest
  (let [calls (atom [])]
    (let [result (run (with-update calls {:version "1.1.0"}) "update")]
      (is (= 0 (:exit result)))
      (is (= "" (:err result)))
      (is (helpers/said? (:out result) :update/current {:version "1.1.0"}) (:out result)))
    (is (not-any? #(#{:download! :replace! :temp-beside!} (first %)) @calls))))

(deftest update-leaves-a-newer-jar-alone-and-says-it-is-up-to-date
  (let [calls (atom [])
        {:keys [exit out err]} (run (with-update calls {:version "2.0.0"}) "update")]
    (is (= 0 exit))
    (is (= "" err))
    (is (helpers/said? out :update/current {:version "2.0.0"}) out)
    (is (not-any? #(#{:download! :replace! :temp-beside!} (first %)) @calls))))

(deftest update-outside-a-jar-exits-1-with-no-network-call
  (let [calls (atom [])]
    (is (= {:exit 1
            :out  ""
            :err  "update only works on an installed knope.jar. This is a checkout, so use git pull.
Checkouts have their own process, and I respect process.
"}
           (run (with-update calls {:running nil}) "update")))
    (is (empty? @calls))))

(deftest update-reports-github-unreachable
  (is (= {:exit 1 :out "" :err "Could not reach GitHub. Check your network and try again.
GitHub isn't answering. I'll keep the binder open.
"}
         (run (with-update (atom []) {:latest-tag {:error/code :update/unreachable}}) "update"))))

(deftest update-reports-no-release-found
  (is (= {:exit 1
          :out  ""
          :err  "Could not find the latest release on GitHub. It may be rate limited, so try again later.
Sometimes the paperwork isn't ready yet.
"}
         (run (with-update (atom []) {:latest-tag {:error/code :update/no-release}}) "update"))))

(deftest update-reports-a-jar-folder-that-is-not-writable
  (let [calls (atom [])
        error {:error/code :update/not-writable :error/details {:path "/opt/knope/knope.jar"}}]
    (is (= {:exit 1
            :out  ""
            :err  "Can't write next to /opt/knope/knope.jar. Check the folder's permissions.
I need permission first. I always get permission first.
"}
           (run (with-update calls {:temp-beside! error}) "update")))
    (is (not-any? #(= :download! (first %)) @calls))))

(deftest update-reports-a-failed-download-and-keeps-the-jar
  (let [calls (atom [])]
    (is (= {:exit 1
            :out  ""
            :err  "The download failed, so the installed jar was left as it was.
Nothing broke. Your knope is exactly as you left it.
"}
           (run (with-update calls {:download! {:error/code :update/download-failed}}) "update")))
    (is (not-any? #(= :replace! (first %)) @calls))))

(deftest update-with-components-that-have-no-database-still-works
  (let [lite (-> (with-update (atom []) {})
                 (select-keys [:clock :release :jar]))]
    (is (= 0 (:exit (run lite "update"))))))

(deftest update-after-the-version-flag-is-an-unknown-option
  (is (= {:exit 1 :out "" :err "Unknown option '--version'.
That's not in any of my binders.
"}
         (run (with-update (atom []) {}) "update" "--version"))))

(deftest usage-lists-the-update-command
  (let [{:keys [exit out err]} (run helpers/*components* "--help")]
    (is (= 0 exit))
    (is (= "" err))
    (is (re-find #"(?m)^\s*(knope )?update\b" out))))
