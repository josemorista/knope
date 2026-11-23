(ns knope.release.jar-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.test :refer [deftest is]]))

(defn- build-jar!
  "Runs `bb build` from the repo root, removing any earlier jar first so the
  test shows the build task makes it. With a version, KNOPE_VERSION is set in
  the build's environment; without one it is removed, so the build is
  unversioned whatever the caller's shell holds. Returns the jar path and the
  build result."
  ([] (build-jar! nil))
  ([version]
   (let [jar (fs/absolutize "target/knope.jar")
         env (cond-> (dissoc (into {} (System/getenv)) "KNOPE_VERSION")
               version (assoc "KNOPE_VERSION" version))]
     (fs/delete-if-exists jar)
     [(str jar) (process/sh {:out :string :err :string :env env} "bb" "build")])))

(defn- bb-jar
  "Runs the jar with bb from an unrelated working directory, with HOME set to
  home and KNOPE_DB removed from the environment."
  [jar home & args]
  (apply process/sh
         {:out :string :err :string
          :dir "/tmp"
          :env (assoc (dissoc (into {} (System/getenv)) "KNOPE_DB" "NO_COLOR")
                      "HOME" (str home))}
         "bb" jar
         args))

(deftest the-built-jar-adds-a-task-from-another-directory-with-a-fresh-home
  (let [home        (fs/create-temp-dir {:prefix "knope-rel-"})
        [jar build] (build-jar!)]
    (try
      (is (= 0 (:exit build)) (:err build))
      (is (fs/exists? jar))
      (when (fs/exists? jar)
        (let [{:keys [exit out err]} (bb-jar jar home "add" "Plan the harvest festival")]
          (is (= 0 exit) err)
          (is (= "Task 1 is on the list! I've already started a binder for it.\n" out))
          (is (fs/exists? (fs/path home ".knope" "tasks.db")))))
      (finally
        (fs/delete-tree home)))))

(defn- bin-knope
  "Runs the checkout launcher from an unrelated working directory, with HOME
  set to home and KNOPE_DB removed from the environment."
  [home & args]
  (apply process/sh
         {:out :string :err :string
          :dir "/tmp"
          :env (assoc (dissoc (into {} (System/getenv)) "KNOPE_DB" "NO_COLOR")
                      "HOME" (str home))}
         (str (fs/absolutize "bin/knope"))
         args))

(deftest a-jar-built-with-a-version-prints-it-and-opens-no-database
  (let [home        (fs/create-temp-dir {:prefix "knope-rel-"})
        [jar build] (build-jar! "1.2.3")]
    (try
      (is (= 0 (:exit build)) (:err build))
      (when (fs/exists? jar)
        (let [{:keys [exit out err]} (bb-jar jar home "--version")]
          (is (= 0 exit) err)
          (is (= "knope 1.2.3\n" out))
          (is (not (fs/exists? (fs/path home ".knope" "tasks.db"))))))
      (finally
        (fs/delete-tree home)))))

(deftest a-jar-built-without-a-version-prints-unversioned
  (let [home        (fs/create-temp-dir {:prefix "knope-rel-"})
        [jar build] (build-jar!)]
    (try
      (is (= 0 (:exit build)) (:err build))
      (when (fs/exists? jar)
        (let [{:keys [exit out err]} (bb-jar jar home "--version")]
          (is (= 0 exit) err)
          (is (= "knope (unversioned)\n" out))
          (is (not (fs/exists? (fs/path home ".knope" "tasks.db"))))))
      (finally
        (fs/delete-tree home)))))

(deftest a-versioned-build-followed-by-an-unversioned-one-prints-unversioned
  (let [home (fs/create-temp-dir {:prefix "knope-rel-"})]
    (try
      (let [[jar versioned] (build-jar! "1.2.3")]
        (is (= 0 (:exit versioned)) (:err versioned))
        (when (fs/exists? jar)
          (is (= "knope 1.2.3\n" (:out (bb-jar jar home "--version"))))))
      (let [[jar unversioned] (build-jar!)]
        (is (= 0 (:exit unversioned)) (:err unversioned))
        (when (fs/exists? jar)
          (is (= "knope (unversioned)\n" (:out (bb-jar jar home "--version"))))))
      (finally
        (fs/delete-tree home)))))

(deftest a-checkout-prints-dev-and-opens-no-database
  (let [home (fs/create-temp-dir {:prefix "knope-rel-"})]
    (try
      (let [{:keys [exit out err]} (bin-knope home "--version")]
        (is (= 0 exit) err)
        (is (= "knope dev\n" out))
        (is (not (fs/exists? (fs/path home ".knope" "tasks.db")))))
      (finally
        (fs/delete-tree home)))))

(deftest update-from-a-checkout-exits-1-with-the-not-a-jar-message
  (let [home (fs/create-temp-dir {:prefix "knope-rel-"})]
    (try
      (let [{:keys [exit out err]} (bin-knope home "update")]
        (is (= 1 exit))
        (is (= "" out))
        (is (= "update only works on an installed knope.jar. This is a checkout, so use git pull.\n" err))
        (is (not (fs/exists? (fs/path home ".knope" "tasks.db")))))
      (finally
        (fs/delete-tree home)))))
