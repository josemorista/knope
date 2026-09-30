(ns gaoshun.integration.cli-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [gaoshun.diplomat.cli :as cli]
            [gaoshun.helpers :as helpers]
            [pod.babashka.go-sqlite3 :as sqlite]))

(use-fixtures :each helpers/with-components)

(defn- run [& argv]
  (cli/run helpers/*components* {:color? false} (vec argv)))

(defn- rows []
  (sqlite/query helpers/*db-path* ["SELECT * FROM tasks ORDER BY id"]))

(deftest add-prints-the-new-id-and-stores-a-todo
  (is (= {:exit 0 :out "Added task 1.\n" :err ""} (run "add" "x")))
  (is (= [{:id          1
           :parent_id   nil
           :title       "x"
           :note        nil
           :status      "todo"
           :created_at  "2026-09-28T12:00:00"
           :due_date    nil
           :finish_date nil}]
         (rows))))

(deftest add-numbers-tasks-in-order
  (is (= "Added task 1.\n" (:out (run "add" "first"))))
  (is (= "Added task 2.\n" (:out (run "add" "second")))))

(deftest add-with-due-tomorrow-stores-the-date
  (is (= {:exit 0 :out "Added task 1.\n" :err ""}
         (run "add" "Write report" "--due" "tomorrow")))
  (is (= [{:id          1
           :parent_id   nil
           :title       "Write report"
           :note        nil
           :status      "todo"
           :created_at  "2026-09-28T12:00:00"
           :due_date    "2026-09-29"
           :finish_date nil}]
         (rows))))

(deftest add-with-an-iso-due-date-stores-it
  (is (= 0 (:exit (run "add" "x" "--due" "2026-10-05"))))
  (is (= ["2026-10-05"] (map :due_date (rows)))))

(deftest add-accepts-a-negative-offset-as-a-due-value
  (is (= {:exit 0 :out "Added task 1.\n" :err ""} (run "add" "x" "--due" "-2d")))
  (is (= ["2026-09-26"] (map :due_date (rows)))))

(deftest add-accepts-a-positive-offset-as-a-due-value
  (is (= 0 (:exit (run "add" "x" "--due" "+3d"))))
  (is (= ["2026-10-01"] (map :due_date (rows)))))

(deftest add-stores-a-note
  (is (= 0 (:exit (run "add" "x" "--note" "for Q3"))))
  (is (= ["for Q3"] (map :note (rows)))))

(deftest add-stores-an-empty-note-as-null
  (is (= 0 (:exit (run "add" "x" "--note" ""))))
  (is (= [nil] (map :note (rows)))))

(deftest add-without-a-title-fails
  (doseq [argv [["add" ""] ["add"] ["add" "   "]]]
    (testing (pr-str argv)
      (is (= {:exit 1 :out "" :err "A task needs a title.\n"} (apply run argv)))
      (is (empty? (rows))))))

(deftest add-with-an-unreadable-due-date-fails
  (is (= {:exit 1
          :out  ""
          :err  "Can't read date 'fri'. Use YYYY-MM-DD, today, tomorrow, yesterday, +Nd or -Nd.\n"}
         (run "add" "x" "--due" "fri")))
  (is (empty? (rows))))

(deftest help-prints-usage-and-exits-zero
  (let [{:keys [exit out err]} (run "--help")]
    (is (= 0 exit))
    (is (= "" err))
    (is (str/includes? out "add"))))

(deftest unknown-command-prints-usage-and-exits-one
  (let [{:keys [exit out err]} (run "frobnicate")]
    (is (= 1 exit))
    (is (str/includes? (str out err) "add"))))

(defn- bb-main [db-path & args]
  (apply process/sh
         {:out :string :err :string
          :extra-env {"GAOSHUN_DB" db-path}}
         "bb" "--config" (str (fs/absolutize "bb.edn"))
         "-m" "gaoshun.diplomat.cli"
         args))

(deftest main-adds-a-task-and-exits-zero
  (let [{:keys [exit out]} (bb-main helpers/*db-path* "add" "x")]
    (is (= 0 exit))
    (is (= "Added task 1.\n" out))
    (is (= ["x"] (map :title (rows))))))

(deftest main-exits-one-with-the-message-on-stderr
  (let [{:keys [exit out err]} (bb-main helpers/*db-path* "add" "")]
    (is (= 1 exit))
    (is (= "" out))
    (is (= "A task needs a title.\n" err))))
