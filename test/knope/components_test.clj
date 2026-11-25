(ns knope.components-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [knope.components :as components]))

(defn- with-temp-dir [f]
  (let [dir (fs/create-temp-dir {:prefix "knope-components-test-"})]
    (try
      (f dir)
      (finally
        (fs/delete-tree dir)))))

(deftest for-args-version-builds-components-without-opening-the-database
  (testing "a database path that cannot be opened does not stop --version"
    (with-temp-dir
      (fn [dir]
        (let [blocker (fs/path dir "not-a-folder")
              db-path (str (fs/path blocker "tasks.db"))]
          (spit (str blocker) "a plain file, so nothing can be created under it")
          (let [result (components/for-args {"KNOPE_DB" db-path "HOME" (str dir)} ["--version"])]
            (is (map? result))
            (is (not (contains? result :sqlite)))
            (is (fn? (get-in result [:jar :running])))
            (is (fn? (get-in result [:jar :version])))
            (is (not (fs/exists? db-path))))))))
  (testing "no database file is created at a path that could be"
    (with-temp-dir
      (fn [dir]
        (let [db-path (str (fs/path dir "tasks.db"))
              result  (components/for-args {"KNOPE_DB" db-path} ["--version"])]
          (is (not (contains? result :sqlite)))
          (is (not (fs/exists? db-path))))))))

(deftest for-args-version-reports-no-running-jar-from-a-checkout
  (with-temp-dir
    (fn [dir]
      (let [result (components/for-args {"KNOPE_DB" (str (fs/path dir "tasks.db"))} ["--version"])]
        (is (nil? ((get-in result [:jar :running]))))))))

(deftest for-args-another-command-opens-the-database
  (with-temp-dir
    (fn [dir]
      (let [db-path (str (fs/path dir "tasks.db"))]
        (doseq [argv [["list"] ["add" "Plan the harvest festival"] []]]
          (testing (pr-str argv)
            (let [result (components/for-args {"KNOPE_DB" db-path} argv)]
              (is (= db-path (:sqlite result)))
              (is (fn? (:clock result)))
              (is (fn? (get-in result [:jar :running])))
              (is (fn? (get-in result [:jar :version]))))))
        (is (fs/exists? db-path))))))

(deftest for-args-reads-only-the-first-argument-for-version
  (with-temp-dir
    (fn [dir]
      (let [db-path (str (fs/path dir "tasks.db"))
            result  (components/for-args {"KNOPE_DB" db-path} ["add" "--version"])]
        (is (= db-path (:sqlite result)))
        (is (fs/exists? db-path))))))

(deftest for-args-update-builds-components-without-opening-the-database
  (testing "a database path that cannot be opened does not stop update"
    (with-temp-dir
      (fn [dir]
        (let [blocker (fs/path dir "not-a-folder")
              db-path (str (fs/path blocker "tasks.db"))]
          (spit (str blocker) "a plain file, so nothing can be created under it")
          (let [result (components/for-args {"KNOPE_DB" db-path "HOME" (str dir)} ["update"])]
            (is (map? result))
            (is (not (contains? result :sqlite)))
            (doseq [k [:running :version :temp-beside! :replace! :discard!]]
              (is (fn? (get-in result [:jar k])) (str k)))
            (doseq [k [:latest-tag :download!]]
              (is (fn? (get-in result [:release k])) (str k)))
            (is (not (fs/exists? db-path))))))))
  (testing "no database file is created at a path that could be"
    (with-temp-dir
      (fn [dir]
        (let [db-path (str (fs/path dir "tasks.db"))
              result  (components/for-args {"KNOPE_DB" db-path} ["update"])]
          (is (not (contains? result :sqlite)))
          (is (not (fs/exists? db-path))))))))

(deftest for-args-gives-the-release-functions-to-every-command
  (with-temp-dir
    (fn [dir]
      (let [result (components/for-args {"KNOPE_DB" (str (fs/path dir "tasks.db"))} ["list"])]
        (is (fn? (get-in result [:release :latest-tag])))
        (is (fn? (get-in result [:release :download!])))))))

(def ^:private completions-argv
  ["org.babashka.cli/completions" "complete" "--shell" "zsh" "--" "tasks" ""])

(deftest for-args-completion-builds-components-without-opening-the-database
  (testing "a database path that cannot be opened does not stop the script"
    (with-temp-dir
      (fn [dir]
        (let [blocker (fs/path dir "not-a-folder")
              db-path (str (fs/path blocker "tasks.db"))]
          (spit (str blocker) "a plain file, so nothing can be created under it")
          (doseq [argv [["completion" "zsh"] ["completion" "bash"] ["completion" "fish"]]]
            (let [result (components/for-args {"KNOPE_DB" db-path "HOME" (str dir)} argv)]
              (is (map? result))
              (is (not (contains? result :sqlite)))
              (is (fn? (:clock result)))
              (is (not (fs/exists? db-path)))))))))
  (testing "no database file is created at a path that could be"
    (with-temp-dir
      (fn [dir]
        (let [db-path (str (fs/path dir "tasks.db"))
              result  (components/for-args {"KNOPE_DB" db-path} ["completion" "zsh"])]
          (is (not (contains? result :sqlite)))
          (is (not (fs/exists? db-path))))))))

(deftest for-args-word-completion-defers-the-database-until-it-is-needed
  (with-temp-dir
    (fn [dir]
      (let [db-path (str (fs/path dir "tasks.db"))
            result  (components/for-args {"KNOPE_DB" db-path} completions-argv)]
        (is (map? result))
        (is (delay? (:sqlite result)))
        (is (not (realized? (:sqlite result))))
        (is (fn? (:clock result)))
        (is (not (fs/exists? db-path)))
        (testing "forcing it opens the database at the path the command would use"
          (is (= db-path (force (:sqlite result))))
          (is (fs/exists? db-path)))))))

(deftest for-args-word-completion-with-an-unopenable-database-fails-only-when-forced
  (with-temp-dir
    (fn [dir]
      (let [blocker (fs/path dir "not-a-folder")
            db-path (str (fs/path blocker "tasks.db"))]
        (spit (str blocker) "a plain file, so nothing can be created under it")
        (let [result (components/for-args {"KNOPE_DB" db-path "HOME" (str dir)} completions-argv)]
          (is (delay? (:sqlite result)))
          (is (thrown? Exception (force (:sqlite result)))))))))
