(ns knope.release.jar-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.test :refer [deftest is]]))

(defn- build-jar!
  "Runs `bb build` from the repo root, removing any earlier jar first so the
  test shows the build task makes it. Returns the jar path and the build result."
  []
  (let [jar (fs/absolutize "target/knope.jar")]
    (fs/delete-if-exists jar)
    [(str jar) (process/sh {:out :string :err :string} "bb" "build")]))

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
