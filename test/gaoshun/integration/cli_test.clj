(ns gaoshun.integration.cli-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [gaoshun.diplomat.cli :as cli]
            [gaoshun.diplomat.sqlite.task :as sqlite.task]
            [gaoshun.gen.task :as gen.task]
            [gaoshun.helpers :as helpers]
            [pod.babashka.go-sqlite3 :as sqlite])
  (:import (java.time LocalDate)))

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

(defn- insert! [overrides]
  (sqlite.task/insert! (gen.task/task (assoc overrides :id nil)) helpers/*db-path*))

(defn- line-has?
  "True when every part appears in the line, in the given order."
  [line parts]
  (boolean (re-find (re-pattern (str/join ".*" (map #(java.util.regex.Pattern/quote %) parts)))
                    line)))

(defn- assert-lines [out expected]
  (let [lines (str/split-lines out)]
    (is (= (count expected) (count lines)) (str "lines:\n" out))
    (doseq [[line parts] (map vector lines expected)]
      (is (line-has? line parts) (str "expected " (pr-str parts) " in " (pr-str line))))))

(def ^:private ansi #"\u001b\[[0-9;]*m")

(deftest list-shows-open-tasks-sorted-by-due-date-with-relative-labels
  (run "add" "Renew passport" "--due" "+10d")
  (run "add" "Tidy desk")
  (run "add" "Plan trip" "--due" "+3d")
  (run "add" "Send invoice" "--due" "tomorrow")
  (run "add" "Pay rent" "--due" "-2d")
  (run "add" "Standup" "--due" "today")
  (insert! {:title "Review PR" :status :doing :due-date (LocalDate/of 2026 9 30)})
  (run "add" "Sort mail")
  (insert! {:title "Old chore" :status :done})
  (let [{:keys [exit out err]} (run "list")]
    (is (= 0 exit))
    (is (= "" err))
    (assert-lines out [["[ ]" "5" "Pay rent" "2d overdue"]
                       ["[ ]" "6" "Standup" "today"]
                       ["[ ]" "4" "Send invoice" "tomorrow"]
                       ["[~]" "7" "Review PR" "in 2d"]
                       ["[ ]" "3" "Plan trip" "in 3d"]
                       ["[ ]" "1" "Renew passport" "2026-10-08"]
                       ["[ ]" "2" "Tidy desk"]
                       ["[ ]" "8" "Sort mail"]])))

(deftest list-hides-closed-tasks
  (run "add" "Open one")
  (insert! {:title "Finished" :status :done})
  (insert! {:title "Abandoned" :status :dropped})
  (let [{:keys [out]} (run "list")]
    (is (str/includes? out "Open one"))
    (is (not (str/includes? out "Finished")))
    (is (not (str/includes? out "Abandoned")))))

(deftest list-all-places-closed-tasks-after-open-ones
  (run "add" "Open later" "--due" "+5d")
  (insert! {:title "Done early" :status :done :finish-date (LocalDate/of 2026 9 20)})
  (insert! {:title "Done recent" :status :done :finish-date (LocalDate/of 2026 9 27)})
  (insert! {:title "Dropped recent" :status :dropped :finish-date (LocalDate/of 2026 9 27)})
  (run "add" "Open soon" "--due" "tomorrow")
  (let [{:keys [exit out err]} (run "list" "--all")]
    (is (= 0 exit))
    (is (= "" err))
    (assert-lines out [["[ ]" "5" "Open soon" "tomorrow"]
                       ["[ ]" "1" "Open later" "in 5d"]
                       ["[-]" "4" "Dropped recent"]
                       ["[x]" "3" "Done recent"]
                       ["[x]" "2" "Done early"]])))

(deftest list-with-nothing-pending-says-so-and-exits-zero
  (is (= {:exit 0 :out "Nothing pending.\n" :err ""} (run "list"))))

(deftest list-with-only-closed-tasks-says-nothing-pending
  (insert! {:title "Finished" :status :done})
  (is (= {:exit 0 :out "Nothing pending.\n" :err ""} (run "list"))))

(deftest list-without-color-has-no-escape-codes
  (run "add" "Pay rent" "--due" "-2d")
  (run "add" "Standup" "--due" "today")
  (run "add" "Plan trip" "--due" "+10d")
  (let [{:keys [exit out]} (run "list")]
    (is (= 0 exit))
    (is (str/includes? out "Pay rent"))
    (is (not (str/includes? out "\u001b")))))

(deftest list-with-color-marks-urgency-and-is-otherwise-identical
  (run "add" "Pay rent" "--due" "-2d")
  (run "add" "Send invoice" "--due" "tomorrow")
  (run "add" "Plan trip" "--due" "+10d")
  (let [plain   (:out (run "list"))
        colored (:out (cli/run helpers/*components* {:color? true} ["list"]))
        line-of (fn [title] (or (first (filter #(str/includes? % title) (str/split-lines colored))) ""))]
    (is (str/includes? (line-of "Pay rent") "\u001b[31m"))
    (is (str/includes? (line-of "Send invoice") "\u001b[33m"))
    (is (str/includes? (line-of "Plan trip") "\u001b[2m"))
    (is (= plain (str/replace colored ansi "")))))

(defn- bb-main-env
  "Like bb-main, from an unrelated working directory and with a controlled
  environment: GAOSHUN_TTY and NO_COLOR come only from env-overrides."
  [env-overrides & args]
  (apply process/sh
         {:out :string :err :string
          :dir "/tmp"
          :env (merge (dissoc (into {} (System/getenv)) "GAOSHUN_TTY" "NO_COLOR")
                      {"GAOSHUN_DB" helpers/*db-path*}
                      env-overrides)}
         "bb" "--config" (str (fs/absolutize "bb.edn"))
         "-m" "gaoshun.diplomat.cli"
         args))

(deftest main-list-works-from-another-directory-and-colors-only-on-a-tty
  (bb-main-env {} "add" "Pay rent" "--due" "-2d")
  (testing "GAOSHUN_TTY=1 turns color on"
    (let [{:keys [exit out]} (bb-main-env {"GAOSHUN_TTY" "1"} "list")]
      (is (= 0 exit))
      (is (str/includes? out "Pay rent"))
      (is (str/includes? out "\u001b["))))
  (testing "GAOSHUN_TTY=1 with NO_COLOR set stays plain"
    (let [{:keys [exit out]} (bb-main-env {"GAOSHUN_TTY" "1" "NO_COLOR" "1"} "list")]
      (is (= 0 exit))
      (is (str/includes? out "Pay rent"))
      (is (not (str/includes? out "\u001b")))))
  (testing "GAOSHUN_TTY=0 stays plain"
    (let [{:keys [exit out]} (bb-main-env {"GAOSHUN_TTY" "0"} "list")]
      (is (= 0 exit))
      (is (str/includes? out "Pay rent"))
      (is (not (str/includes? out "\u001b")))))
  (testing "GAOSHUN_TTY unset stays plain"
    (let [{:keys [exit out]} (bb-main-env {} "list")]
      (is (= 0 exit))
      (is (str/includes? out "Pay rent"))
      (is (not (str/includes? out "\u001b"))))))
