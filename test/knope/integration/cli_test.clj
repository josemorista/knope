(ns knope.integration.cli-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [knope.diplomat.cli :as cli]
            [knope.diplomat.sqlite.task :as sqlite.task]
            [knope.gen.task :as gen.task]
            [knope.helpers :as helpers]
            [pod.babashka.go-sqlite3 :as sqlite])
  (:import (java.time LocalDate LocalDateTime)))

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

(deftest a-value-option-without-a-value-fails
  (doseq [[argv message] [[["add" "x" "--due"] "--due needs a value.\n"]
                          [["add" "x" "--note"] "--note needs a value.\n"]
                          [["list" "--page"] "--page needs a value.\n"]]]
    (testing (pr-str argv)
      (is (= {:exit 1 :out "" :err message} (apply run argv)))
      (is (empty? (rows))))))

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
          :extra-env {"KNOPE_DB" db-path}}
         "bb" "--config" (str (fs/absolutize "bb.edn"))
         "-m" "knope.diplomat.cli"
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

(defn- add-open-tasks! [n]
  (doseq [i (range 1 (inc n))]
    (insert! {:title (str "Task " i)})))

(deftest list-shows-fifty-tasks-and-points-to-the-next-page
  (add-open-tasks! 51)
  (let [{:keys [exit out err]} (run "list")
        lines                  (str/split-lines out)]
    (is (= 0 exit))
    (is (= "" err))
    (is (= 51 (count lines)))
    (is (line-has? (nth lines 49) ["[ ]" "50" "Task 50"]))
    (is (= "More on page 1: knope list --page 1" (last lines)))))

(deftest list-page-one-shows-the-rest-without-a-pointer
  (add-open-tasks! 51)
  (is (= {:exit 0 :out "[ ] 51 Task 51\n" :err ""} (run "list" "--page" "1"))))

(deftest list-with-exactly-fifty-tasks-has-no-pointer
  (add-open-tasks! 50)
  (let [lines (str/split-lines (:out (run "list")))]
    (is (= 50 (count lines)))
    (is (line-has? (last lines) ["[ ]" "50" "Task 50"]))))

(deftest list-past-the-last-page-says-nothing-pending
  (add-open-tasks! 3)
  (is (= {:exit 0 :out "Nothing pending.\n" :err ""} (run "list" "--page" "1")))
  (is (= {:exit 0 :out "Nothing pending.\n" :err ""} (run "list" "--all" "--page" "4"))))

(deftest list-all-pages-closed-tasks-and-keeps-all-in-the-pointer
  (add-open-tasks! 50)
  (insert! {:title "Finished" :status :done})
  (insert! {:title "Abandoned" :status :dropped})
  (let [lines (str/split-lines (:out (run "list" "--all")))]
    (is (= 51 (count lines)))
    (is (= "More on page 1: knope list --all --page 1" (last lines))))
  (let [{:keys [exit out]} (run "list" "--all" "--page" "1")]
    (is (= 0 exit))
    (assert-lines out [["[-]" "52" "Abandoned"]
                       ["[x]" "51" "Finished"]])))

(deftest list-with-a-bad-page-fails
  (doseq [input ["abc" "-1" "1.5"]]
    (testing input
      (is (= {:exit 1 :out "" :err "--page needs a number.\n"}
             (run "list" "--page" input))))))

(deftest add-with-a-parent-stores-a-subtask
  (run "add" "Parent")
  (is (= {:exit 0 :out "Added task 2.\n" :err ""} (run "add" "step" "--parent" "1")))
  (is (= [nil 1] (map :parent_id (rows)))))

(deftest add-with-a-parent-that-is-a-subtask-fails
  (run "add" "Parent")
  (run "add" "step" "--parent" "1")
  (is (= {:exit 1 :out "" :err "Task 2 is a subtask; subtasks can't have subtasks.\n"}
         (run "add" "deeper" "--parent" "2")))
  (is (= 2 (count (rows)))))

(deftest add-with-a-missing-parent-fails
  (run "add" "Parent")
  (is (= {:exit 1 :out "" :err "Task 99 not found.\n"} (run "add" "step" "--parent" "99")))
  (is (= 1 (count (rows)))))

(deftest add-with-a-bad-parent-id-fails
  (run "add" "Parent")
  (doseq [input ["abc" "0" "-1"]]
    (testing input
      (is (= {:exit 1 :out "" :err (str "'" input "' is not a task id.\n")}
             (run "add" "step" "--parent" input)))))
  (is (= 1 (count (rows)))))

(deftest list-indents-subtasks-under-their-parent-with-progress
  (run "add" "Parent")
  (run "add" "Later step" "--parent" "1")
  (run "add" "Soon step" "--parent" "1" "--due" "tomorrow")
  (insert! {:title "Finished step" :parent-id 1 :status :done})
  (insert! {:title "Dropped step" :parent-id 1 :status :dropped})
  (run "add" "Other" "--due" "today")
  (let [{:keys [exit out err]} (run "list")
        lines                  (str/split-lines out)]
    (is (= 0 exit))
    (is (= "" err))
    (assert-lines out [["[ ]" "6" "Other" "today"]
                       ["[ ]" "1" "Parent"]
                       ["[ ]" "3" "Soon step" "tomorrow"]
                       ["[ ]" "2" "Later step"]])
    (is (str/includes? (get lines 1 "") "1/3"))
    (is (re-find #"^\[ \] 6 " (get lines 0 "")))
    (is (re-find #"^\[ \] 1 " (get lines 1 "")))
    (is (re-find #"^  \[ \] 3 " (get lines 2 "")))
    (is (re-find #"^  \[ \] 2 " (get lines 3 "")))))

(deftest list-shows-a-fresh-parent-as-zero-over-one
  (run "add" "Parent")
  (run "add" "step" "--parent" "1")
  (let [lines (str/split-lines (:out (run "list")))]
    (is (= 2 (count lines)))
    (is (str/includes? (first lines) "0/1"))
    (is (re-find #"^  \[ \] 2 .*step" (second lines)))))

(deftest list-all-shows-closed-subtasks-after-open-ones
  (run "add" "Parent")
  (run "add" "Open step" "--parent" "1")
  (insert! {:title "Old step" :parent-id 1 :status :done :finish-date (LocalDate/of 2026 9 20)})
  (insert! {:title "Recent step" :parent-id 1 :status :done :finish-date (LocalDate/of 2026 9 27)})
  (let [{:keys [out]} (run "list" "--all")]
    (assert-lines out [["[ ]" "1" "Parent"]
                       ["[ ]" "2" "Open step"]
                       ["[x]" "4" "Recent step"]
                       ["[x]" "3" "Old step"]])
    (is (every? #(re-find #"^  " %) (rest (str/split-lines out))))))

(deftest list-keeps-a-closed-parent-as-context-for-an-open-subtask
  (insert! {:title "Closed parent" :status :done})
  (insert! {:title "Open step" :parent-id 1})
  (insert! {:title "Closed step" :parent-id 1 :status :done})
  (let [{:keys [exit out]} (run "list")
        lines              (str/split-lines out)]
    (is (= 0 exit))
    (assert-lines out [["[x]" "1" "Closed parent"]
                       ["[ ]" "2" "Open step"]])
    (is (re-find #"^\[x\] 1 " (get lines 0 "")))
    (is (re-find #"^  \[ \] 2 " (get lines 1 "")))))

(deftest list-dims-a-closed-parent-shown-as-context
  (insert! {:title "Closed parent" :status :done})
  (insert! {:title "Open step" :parent-id 1})
  (let [colored (:out (cli/run helpers/*components* {:color? true} ["list"]))
        parent  (or (first (filter #(str/includes? % "Closed parent") (str/split-lines colored))) "")]
    (is (str/includes? parent "\u001b[2m"))
    (is (= (:out (run "list")) (str/replace colored ansi "")))))

(deftest list-omits-a-closed-parent-whose-subtasks-are-all-closed
  (insert! {:title "Closed parent" :status :done})
  (insert! {:title "Closed step" :parent-id 1 :status :done})
  (insert! {:title "Dropped step" :parent-id 1 :status :dropped})
  (insert! {:title "Context parent" :status :done})
  (insert! {:title "Open step" :parent-id 4})
  (let [{:keys [out]} (run "list")]
    (assert-lines out [["[x]" "4" "Context parent"]
                       ["[ ]" "5" "Open step"]])
    (is (re-find #"^  \[ \] 5 " (get (str/split-lines out) 1 "")))))

(deftest list-pages-by-root-so-a-parent-stays-with-its-subtasks
  (add-open-tasks! 51)
  (insert! {:title "Step A" :parent-id 50})
  (insert! {:title "Step B" :parent-id 50})
  (let [lines (str/split-lines (:out (run "list")))]
    (is (= 53 (count lines)))
    (is (line-has? (get lines 49 "") ["[ ]" "50" "Task 50"]))
    (is (re-find #"^  \[ \] 52 " (get lines 50 "")))
    (is (re-find #"^  \[ \] 53 " (get lines 51 "")))
    (is (= "More on page 1: knope list --page 1" (last lines))))
  (is (= {:exit 0 :out "[ ] 51 Task 51\n" :err ""} (run "list" "--page" "1"))))

(deftest list-does-not-leak-the-subtasks-of-the-next-page-root
  (add-open-tasks! 51)
  (insert! {:title "Step of 51" :parent-id 51})
  (let [lines (str/split-lines (:out (run "list")))]
    (is (= 51 (count lines)))
    (is (not-any? #(str/includes? % "Step of 51") lines))
    (is (= "More on page 1: knope list --page 1" (last lines))))
  (is (= "[ ] 51 Task 51  0/1\n  [ ] 52 Step of 51\n" (:out (run "list" "--page" "1")))))

(deftest list-with-exactly-fifty-roots-and-subtasks-has-no-pointer
  (add-open-tasks! 50)
  (insert! {:title "Step" :parent-id 50})
  (let [lines (str/split-lines (:out (run "list")))]
    (is (= 51 (count lines)))
    (is (re-find #"^  \[ \] 51 " (last lines)))))

(defn- bb-main-env
  "Like bb-main, from an unrelated working directory and with a controlled
  environment: NO_COLOR is removed."
  [& args]
  (apply process/sh
         {:out :string :err :string
          :dir "/tmp"
          :env (merge (dissoc (into {} (System/getenv)) "NO_COLOR")
                      {"KNOPE_DB" helpers/*db-path*})}
         "bb" "--config" (str (fs/absolutize "bb.edn"))
         "-m" "knope.diplomat.cli"
         args))

(deftest main-list-works-from-another-directory-and-stays-plain-when-piped
  (bb-main-env "add" "Pay rent" "--due" "-2d")
  (let [{:keys [exit out]} (bb-main-env "list")]
    (is (= 0 exit))
    (is (str/includes? out "Pay rent"))
    (is (not (str/includes? out "\u001b")))))

(deftest color-is-on-for-a-terminal-without-no-color
  (is (true? (cli/color? {} true)))
  (is (true? (cli/color? {"HOME" "/home/someone"} true))))

(deftest color-is-off-for-a-terminal-with-no-color-set
  (testing "NO_COLOR set to a value"
    (is (false? (cli/color? {"NO_COLOR" "1"} true))))
  (testing "NO_COLOR set to an empty value still counts as set"
    (is (false? (cli/color? {"NO_COLOR" ""} true)))))

(deftest color-is-off-when-stdout-is-not-a-terminal
  (is (false? (cli/color? {} false)))
  (is (false? (cli/color? {"NO_COLOR" "1"} false))))

(defn- task-row
  "The stored row of a task made by (insert! {...}) with the generator defaults."
  [status finish-date]
  {:id          1
   :parent_id   nil
   :title       "Write report"
   :note        nil
   :status      status
   :created_at  "2026-09-28T12:00:00"
   :due_date    nil
   :finish_date finish-date})

(defn- run-on
  "Runs argv with the clock set to the given date-time."
  [now & argv]
  (cli/run (assoc helpers/*components* :clock (constantly now)) {:color? false} (vec argv)))

(deftest start-moves-a-todo-task-to-doing
  (insert! {})
  (is (= {:exit 0 :out "Task 1 started.\n" :err ""} (run "start" "1")))
  (is (= [(task-row "doing" nil)] (rows))))

(deftest start-on-a-doing-task-says-so-and-changes-nothing
  (insert! {:status :doing})
  (is (= {:exit 0 :out "Task 1 is already in progress.\n" :err ""} (run "start" "1")))
  (is (= [(task-row "doing" nil)] (rows))))

(deftest done-closes-an-open-task-with-the-clock-date
  (doseq [status [:todo :doing]]
    (testing status
      (insert! {:status status})
      (is (= {:exit 0 :out "Task 1 marked done.\n" :err ""} (run "done" "1")))
      (is (= [(task-row "done" "2026-09-28")] (rows)))
      (sqlite/execute! helpers/*db-path* ["DELETE FROM tasks"])
      (sqlite/execute! helpers/*db-path* ["DELETE FROM sqlite_sequence WHERE name = 'tasks'"]))))

(deftest a-repeated-done-keeps-the-original-finish-date
  (insert! {})
  (run "done" "1")
  (is (= {:exit 0 :out "Task 1 is already done.\n" :err ""}
         (run-on (LocalDateTime/of 2026 10 5 9 0 0) "done" "1")))
  (is (= [(task-row "done" "2026-09-28")] (rows))))

(deftest drop-closes-an-open-task-with-the-clock-date
  (insert! {:status :doing})
  (is (= {:exit 0 :out "Task 1 dropped.\n" :err ""} (run "drop" "1")))
  (is (= [(task-row "dropped" "2026-09-28")] (rows))))

(deftest a-repeated-drop-keeps-the-original-finish-date
  (insert! {})
  (run "drop" "1")
  (is (= {:exit 0 :out "Task 1 is already dropped.\n" :err ""}
         (run-on (LocalDateTime/of 2026 10 5 9 0 0) "drop" "1")))
  (is (= [(task-row "dropped" "2026-09-28")] (rows))))

(deftest reopen-returns-closed-tasks-to-todo-and-clears-the-finish-date
  (doseq [status [:done :dropped]]
    (testing status
      (insert! {:status status})
      (is (= {:exit 0 :out "Task 1 reopened.\n" :err ""} (run "reopen" "1")))
      (is (= [(task-row "todo" nil)] (rows)))
      (sqlite/execute! helpers/*db-path* ["DELETE FROM tasks"])
      (sqlite/execute! helpers/*db-path* ["DELETE FROM sqlite_sequence WHERE name = 'tasks'"]))))

(deftest reopen-on-an-open-task-says-so-and-changes-nothing
  (insert! {:status :doing})
  (is (= {:exit 0 :out "Task 1 is already open.\n" :err ""} (run "reopen" "1")))
  (is (= [(task-row "doing" nil)] (rows))))

(deftest an-illegal-move-fails-and-changes-nothing
  (doseq [[status command message] [[:done "drop" "Task 1 is done; reopen it first.\n"]
                                    [:done "start" "Task 1 is done; reopen it first.\n"]
                                    [:dropped "done" "Task 1 is dropped; reopen it first.\n"]
                                    [:dropped "start" "Task 1 is dropped; reopen it first.\n"]]]
    (testing (str command " on " status)
      (insert! {:status status})
      (is (= {:exit 1 :out "" :err message} (run command "1")))
      (is (= [(task-row (name status) "2026-09-28")] (rows)))
      (sqlite/execute! helpers/*db-path* ["DELETE FROM tasks"])
      (sqlite/execute! helpers/*db-path* ["DELETE FROM sqlite_sequence WHERE name = 'tasks'"]))))

(deftest a-transition-on-an-unknown-id-fails
  (doseq [command ["start" "done" "drop" "reopen"]]
    (testing command
      (is (= {:exit 1 :out "" :err "Task 99 not found.\n"} (run command "99"))))))

(deftest a-transition-with-a-bad-id-fails
  (insert! {})
  (doseq [command ["start" "done" "drop" "reopen"]
          input   ["abc" "0" "-1"]]
    (testing (str command " " input)
      (is (= {:exit 1 :out "" :err (str "'" input "' is not a task id.\n")} (run command input)))))
  (is (= [(task-row "todo" nil)] (rows))))

(deftest done-on-a-parent-with-an-open-subtask-shows-it-as-a-dimmed-header
  (run "add" "Parent")
  (run "add" "Step" "--parent" "1")
  (is (= {:exit 0 :out "Task 1 marked done.\n" :err ""} (run "done" "1")))
  (let [{:keys [exit out err]} (run "list")
        colored               (:out (cli/run helpers/*components* {:color? true} ["list"]))
        header                (or (first (filter #(str/includes? % "Parent") (str/split-lines colored))) "")]
    (is (= 0 exit))
    (is (= "" err))
    (assert-lines out [["[x]" "1" "Parent"]
                       ["[ ]" "2" "Step"]])
    (is (re-find #"^\[x\] 1 " (first (str/split-lines out))))
    (is (re-find #"^  \[ \] 2 " (second (str/split-lines out))))
    (is (str/includes? header "\u001b[2m"))))

(deftest a-transition-without-an-id-prints-usage-and-exits-one
  (doseq [command ["start" "done" "drop" "reopen"]]
    (testing command
      (is (= {:exit 1 :out "" :err @#'cli/usage} (run command))))))

(defn- extra-argument [input]
  {:exit 1 :out "" :err (str "Unexpected argument '" input "'. Put text with spaces in quotes.\n")})

(defn- unknown-option [option]
  {:exit 1 :out "" :err (str "Unknown option '--" option "'.\n")})

(deftest add-with-an-unquoted-multi-word-title-is-refused
  (is (= (extra-argument "milk") (run "add" "buy" "milk")))
  (is (= (extra-argument "y") (run "add" "x" "y" "z")))
  (is (empty? (rows))))

(deftest add-with-an-unquoted-multi-word-note-is-refused
  (is (= (extra-argument "milk") (run "add" "x" "--note" "buy" "milk")))
  (is (empty? (rows))))

(deftest add-with-quoted-multi-word-text-stores-it-whole
  (is (= {:exit 0 :out "Added task 1.\n" :err ""} (run "add" "buy milk")))
  (is (= {:exit 0 :out "Added task 2.\n" :err ""} (run "add" "x" "--note" "buy milk")))
  (is (= [{:id          1
           :parent_id   nil
           :title       "buy milk"
           :note        nil
           :status      "todo"
           :created_at  "2026-09-28T12:00:00"
           :due_date    nil
           :finish_date nil}
          {:id          2
           :parent_id   nil
           :title       "x"
           :note        "buy milk"
           :status      "todo"
           :created_at  "2026-09-28T12:00:00"
           :due_date    nil
           :finish_date nil}]
         (rows))))

(deftest list-with-an-extra-word-is-refused
  (insert! {:title "Open one"})
  (is (= (extra-argument "extra") (run "list" "extra"))))

(deftest a-transition-with-an-extra-word-is-refused-and-changes-nothing
  (insert! {:title "One"})
  (insert! {:title "Two"})
  (insert! {:title "Three"})
  (let [before (rows)]
    (doseq [command ["start" "done" "drop" "reopen"]]
      (testing command
        (is (= (extra-argument "4") (run command "3" "4")))))
    (is (= before (rows)))))

(deftest add-with-an-unknown-option-is-refused
  (is (= (unknown-option "bogus") (run "add" "x" "--bogus" "y")))
  (is (= (unknown-option "title") (run "add" "--title" "x" "y")))
  (is (empty? (rows))))

(deftest add-with-an-unknown-short-option-names-it-as-typed
  (is (= {:exit 1 :out "" :err "Unknown option '-d'.\n"} (run "add" "x" "-d" "today")))
  (is (empty? (rows))))

(deftest add-with-an-unknown-option-and-value-names-only-the-option
  (is (= {:exit 1 :out "" :err "Unknown option '--bogus'.\n"} (run "add" "x" "--bogus=1")))
  (is (empty? (rows))))

(deftest a-transition-with-an-unknown-option-is-refused-and-changes-nothing
  (insert! {:title "One"})
  (insert! {:title "Two"})
  (insert! {:title "Three"})
  (let [before (rows)]
    (doseq [command ["start" "done" "drop" "reopen"]]
      (testing command
        (is (= (unknown-option "bogus") (run command "--bogus" "x" "3")))))
    (is (= before (rows)))))

(deftest list-with-an-unknown-option-is-refused
  (insert! {:title "Open one"})
  (is (= (unknown-option "bogus") (run "list" "--bogus"))))

(deftest only-the-top-level-accepts-help
  (is (= (unknown-option "help") (run "add" "--help")))
  (is (= (unknown-option "help") (run "list" "--help")))
  (is (= 0 (:exit (run "--help")))))

(deftest the-shape-is-checked-before-any-value
  (insert! {:title "One"})
  (let [before (rows)]
    (testing "an unknown option is reported before a blank title"
      (is (= (unknown-option "bogus") (run "add" "" "--bogus" "y"))))
    (testing "an unknown option is reported before an unreadable date"
      (is (= (unknown-option "bogus") (run "add" "x" "--due" "fri" "--bogus" "y"))))
    (testing "an extra word is reported before an unreadable date"
      (is (= (extra-argument "y") (run "add" "x" "y" "--due" "fri"))))
    (testing "an extra word is reported before a bad parent"
      (is (= (extra-argument "y") (run "add" "x" "y" "--parent" "abc"))))
    (testing "an unknown option is reported before an extra word"
      (is (= (unknown-option "bogus") (run "add" "buy" "milk" "--bogus" "y"))))
    (testing "an extra word is reported before a bad id"
      (is (= (extra-argument "4") (run "done" "abc" "4"))))
    (testing "an unknown option is reported before a bad id"
      (is (= (unknown-option "bogus") (run "done" "--bogus" "abc"))))
    (testing "an extra word is reported before a bad page"
      (is (= (extra-argument "extra") (run "list" "--page" "abc" "extra"))))
    (is (= before (rows)))))

(deftest help-prints-the-usage-with-descriptions-in-one-column
  (is (= {:exit 0
          :out  "Usage: knope <command> [options]

Commands:
  add <title> [--due DATE] [--note TEXT] [--parent ID]          Add a task, or a subtask of ID.
  list [--all] [--page N]                                       Show pending tasks, 50 per page; --all includes closed ones.
  start ID                                                      Mark a task as in progress.
  done ID                                                       Mark a task as done.
  drop ID                                                       Mark a task as dropped.
  reopen ID                                                     Move a done or dropped task back to todo.
  edit ID [--title T] [--due DATE] [--note TEXT] [--clear-due]  Change a task's title, due date or note.
  rm ID [--force]                                               Delete a task; --force also deletes its subtasks.
  day [DATE]                                                    Show what was done, dropped and open on a day; today by default.
  search TEXT                                                   Find tasks whose title or note has every word as a prefix.
"
          :err  ""}
         (run "--help"))))

(deftest list-renders-a-dimmed-context-row-dim-as-a-whole-with-no-urgency-color
  (insert! {:title "Closed parent" :status :done :due-date (LocalDate/of 2026 9 26)})
  (insert! {:title "Open step" :parent-id 1})
  (let [plain   (:out (run "list"))
        colored (:out (cli/run helpers/*components* {:color? true} ["list"]))
        parent  (or (first (filter #(str/includes? % "Closed parent") (str/split-lines colored))) "")
        plain-parent (or (first (filter #(str/includes? % "Closed parent") (str/split-lines plain))) "")]
    (is (str/includes? plain-parent "2d overdue"))
    (is (not (str/includes? parent "\u001b[31m")))
    (is (= (str "\u001b[2m" plain-parent "\u001b[0m") parent))
    (is (= plain (str/replace colored ansi "")))))

(defn- edit-seed!
  "Stores a doing task with a note and a due date, so an edit shows which
  fields it leaves alone."
  []
  (insert! {:title "Write report" :note "old note" :status :doing :due-date (LocalDate/of 2026 10 5)}))

(defn- edit-row
  "The stored row of the task made by edit-seed!, with the given columns replaced."
  [changes]
  (merge {:id          1
          :parent_id   nil
          :title       "Write report"
          :note        "old note"
          :status      "doing"
          :created_at  "2026-09-28T12:00:00"
          :due_date    "2026-10-05"
          :finish_date nil}
         changes))

(def ^:private updated-task-1 {:exit 0 :out "Updated task 1.\n" :err ""})

(deftest edit-changes-the-title-and-leaves-every-other-field
  (edit-seed!)
  (is (= updated-task-1 (run "edit" "1" "--title" "new name")))
  (is (= [(edit-row {:title "new name"})] (rows))))

(deftest edit-changes-the-due-date
  (edit-seed!)
  (is (= updated-task-1 (run "edit" "1" "--due" "tomorrow")))
  (is (= [(edit-row {:due_date "2026-09-29"})] (rows))))

(deftest edit-sets-a-due-date-on-a-task-without-one
  (insert! {})
  (is (= updated-task-1 (run "edit" "1" "--due" "2026-11-01")))
  (is (= [(assoc (task-row "todo" nil) :due_date "2026-11-01")] (rows))))

(deftest edit-changes-the-note
  (edit-seed!)
  (is (= updated-task-1 (run "edit" "1" "--note" "buy milk")))
  (is (= [(edit-row {:note "buy milk"})] (rows))))

(deftest edit-with-an-empty-note-clears-the-note
  (edit-seed!)
  (is (= updated-task-1 (run "edit" "1" "--note" "")))
  (is (= [(edit-row {:note nil})] (rows))))

(deftest edit-with-clear-due-removes-the-due-date
  (edit-seed!)
  (is (= updated-task-1 (run "edit" "1" "--clear-due")))
  (is (= [(edit-row {:due_date nil})] (rows))))

(deftest edit-applies-several-options-at-once
  (edit-seed!)
  (is (= updated-task-1 (run "edit" "1" "--title" "new name" "--note" "" "--clear-due")))
  (is (= [(edit-row {:title "new name" :note nil :due_date nil})] (rows)))
  (is (= updated-task-1 (run "edit" "1" "--due" "+3d" "--note" "later")))
  (is (= [(edit-row {:title "new name" :note "later" :due_date "2026-10-01"})] (rows))))

(deftest edit-takes-the-options-before-the-id
  (edit-seed!)
  (is (= updated-task-1 (run "edit" "--title" "new name" "1")))
  (is (= [(edit-row {:title "new name"})] (rows))))

(deftest edit-accepts-a-negative-offset-as-a-due-value
  (edit-seed!)
  (is (= updated-task-1 (run "edit" "1" "--due" "-2d")))
  (is (= [(edit-row {:due_date "2026-09-26"})] (rows))))

(deftest edit-leaves-status-finish-date-parent-and-other-tasks-alone
  (insert! {:title "Parent"})
  (insert! {:title "Step" :parent-id 1 :status :done :note "n" :due-date (LocalDate/of 2026 10 5)})
  (insert! {:title "Other"})
  (let [[parent _ other] (rows)]
    (is (= {:exit 0 :out "Updated task 2.\n" :err ""} (run "edit" "2" "--title" "Renamed step")))
    (is (= [parent
            {:id          2
             :parent_id   1
             :title       "Renamed step"
             :note        "n"
             :status      "done"
             :created_at  "2026-09-28T12:00:00"
             :due_date    "2026-10-05"
             :finish_date "2026-09-28"}
            other]
           (rows)))))

(deftest edit-with-no-options-is-refused-and-changes-nothing
  (edit-seed!)
  (let [before (rows)]
    (is (= {:exit 1 :out "" :err "Nothing to change. Pass --title, --due, --note or --clear-due.\n"}
           (run "edit" "1")))
    (is (= before (rows)))))

(deftest edit-with-a-due-date-and-clear-due-is-refused-and-changes-nothing
  (edit-seed!)
  (let [before (rows)]
    (is (= {:exit 1 :out "" :err "--due and --clear-due can't be used together.\n"}
           (run "edit" "1" "--due" "tomorrow" "--clear-due")))
    (is (= {:exit 1 :out "" :err "--due and --clear-due can't be used together.\n"}
           (run "edit" "1" "--clear-due" "--title" "new name" "--due" "2026-11-01")))
    (is (= before (rows)))))

(deftest edit-with-a-blank-title-is-refused-and-changes-nothing
  (edit-seed!)
  (let [before (rows)]
    (doseq [title ["" "   "]]
      (testing (pr-str title)
        (is (= {:exit 1 :out "" :err "A task needs a title.\n"}
               (run "edit" "1" "--title" title)))))
    (is (= before (rows)))))

(deftest edit-with-an-unreadable-date-is-refused-and-changes-nothing
  (edit-seed!)
  (let [before (rows)]
    (is (= {:exit 1
            :out  ""
            :err  "Can't read date 'fri'. Use YYYY-MM-DD, today, tomorrow, yesterday, +Nd or -Nd.\n"}
           (run "edit" "1" "--due" "fri")))
    (is (= before (rows)))))

(deftest edit-with-an-unquoted-multi-word-value-is-refused-and-changes-nothing
  (edit-seed!)
  (let [before (rows)]
    (is (= (extra-argument "name") (run "edit" "1" "--title" "new" "name")))
    (is (= (extra-argument "milk") (run "edit" "1" "--note" "buy" "milk")))
    (is (= before (rows)))))

(deftest edit-with-an-unknown-option-is-refused-and-changes-nothing
  (edit-seed!)
  (let [before (rows)]
    (is (= (unknown-option "bogus") (run "edit" "1" "--bogus" "x")))
    (is (= (unknown-option "parent") (run "edit" "1" "--parent" "2")))
    (is (= (unknown-option "status") (run "edit" "1" "--status" "done")))
    (is (= before (rows)))))

(deftest edit-reports-the-shape-before-any-value
  (edit-seed!)
  (let [before (rows)]
    (is (= (unknown-option "bogus") (run "edit" "1" "--title" "" "--bogus" "x")))
    (is (= (extra-argument "milk") (run "edit" "1" "--due" "fri" "--note" "buy" "milk")))
    (is (= (extra-argument "4") (run "edit" "abc" "--title" "x" "4")))
    (is (= before (rows)))))

(deftest edit-on-an-unknown-id-fails
  (edit-seed!)
  (let [before (rows)]
    (is (= {:exit 1 :out "" :err "Task 99 not found.\n"} (run "edit" "99" "--title" "x")))
    (is (= {:exit 1 :out "" :err "Task 99 not found.\n"} (run "edit" "99" "--clear-due")))
    (is (= before (rows)))))

(deftest edit-with-a-bad-id-fails
  (edit-seed!)
  (let [before (rows)]
    (doseq [input ["abc" "0" "-1"]]
      (testing input
        (is (= {:exit 1 :out "" :err (str "'" input "' is not a task id.\n")}
               (run "edit" input "--title" "x")))))
    (is (= before (rows)))))

(deftest edit-without-an-id-prints-usage-and-exits-one
  (is (= {:exit 1 :out "" :err @#'cli/usage} (run "edit"))))

(defn- seed-family!
  "Two other tasks (the first with a subtask), then task 3 with n subtasks, then an unrelated task.
  Returns the stored rows."
  [n]
  (insert! {:title "Other parent"})
  (insert! {:title "Other step" :parent-id 1 :note "keep me" :due-date (LocalDate/of 2026 10 5)})
  (insert! {:title "Parent" :status :doing})
  (dotimes [i n]
    (insert! {:title (str "Step " (inc i)) :parent-id 3 :status (if (even? i) :todo :done)}))
  (insert! {:title "Unrelated" :due-date (LocalDate/of 2026 10 1)})
  (rows))

(defn- without-family
  "The rows left once task 3 and its subtasks are gone."
  [before]
  (vec (remove #(or (= 3 (:id %)) (= 3 (:parent_id %))) before)))

(deftest rm-deletes-a-task-without-subtasks
  (insert! {:title "Keep" :note "n"})
  (insert! {:title "Typo"})
  (insert! {:title "Also keep" :due-date (LocalDate/of 2026 10 1)})
  (let [[keep _ also-keep] (rows)]
    (is (= {:exit 0 :out "Deleted task 2.\n" :err ""} (run "rm" "2")))
    (is (= [keep also-keep] (rows)))))

(deftest rm-deletes-the-only-task
  (insert! {})
  (is (= {:exit 0 :out "Deleted task 1.\n" :err ""} (run "rm" "1")))
  (is (= [] (rows))))

(deftest rm-deletes-one-subtask-and-leaves-its-parent-and-siblings
  (let [before (seed-family! 2)]
    (is (= 6 (count before)))
    (is (= {:exit 0 :out "Deleted task 4.\n" :err ""} (run "rm" "4")))
    (is (= (vec (remove #(= 4 (:id %)) before)) (rows)))))

(deftest rm-of-a-parent-without-force-is-refused-and-deletes-nothing
  (let [before (seed-family! 2)]
    (is (= 6 (count before)))
    (is (= {:exit 1 :out "" :err "Task 3 has 2 subtasks. Use --force to delete them too.\n"}
           (run "rm" "3")))
    (is (= before (rows)))))

(deftest rm-of-a-parent-with-one-subtask-reads-in-the-singular
  (let [before (seed-family! 1)]
    (is (= 5 (count before)))
    (is (= {:exit 1 :out "" :err "Task 3 has 1 subtask. Use --force to delete it too.\n"}
           (run "rm" "3")))
    (is (= before (rows)))))

(deftest rm-of-a-parent-with-an-all-closed-family-still-needs-force
  (insert! {:title "Parent" :status :done})
  (insert! {:title "Step" :parent-id 1 :status :dropped})
  (let [before (rows)]
    (is (= {:exit 1 :out "" :err "Task 1 has 1 subtask. Use --force to delete it too.\n"}
           (run "rm" "1")))
    (is (= before (rows)))))

(deftest rm-with-force-after-the-id-deletes-the-parent-and-its-subtasks
  (let [before (seed-family! 2)]
    (is (= {:exit 0 :out "Deleted task 3 and 2 subtasks.\n" :err ""} (run "rm" "3" "--force")))
    (is (= (without-family before) (rows)))
    (is (= [1 2 6] (mapv :id (rows))))))

(deftest rm-with-force-before-the-id-deletes-the-parent-and-its-subtasks
  (let [before (seed-family! 2)]
    (is (= {:exit 0 :out "Deleted task 3 and 2 subtasks.\n" :err ""} (run "rm" "--force" "3")))
    (is (= (without-family before) (rows)))
    (is (= [1 2 6] (mapv :id (rows))))))

(deftest forced-rm-of-a-parent-with-one-subtask-reads-in-the-singular-with-force-after-the-id
  (let [before (seed-family! 1)]
    (is (= 5 (count before)))
    (is (= {:exit 0 :out "Deleted task 3 and 1 subtask.\n" :err ""} (run "rm" "3" "--force")))
    (is (= (without-family before) (rows)))
    (is (= [1 2 5] (mapv :id (rows))))))

(deftest forced-rm-of-a-parent-with-one-subtask-reads-in-the-singular-with-force-before-the-id
  (let [before (seed-family! 1)]
    (is (= 5 (count before)))
    (is (= {:exit 0 :out "Deleted task 3 and 1 subtask.\n" :err ""} (run "rm" "--force" "3")))
    (is (= (without-family before) (rows)))
    (is (= [1 2 5] (mapv :id (rows))))))

(deftest forced-rm-of-a-task-without-subtasks-deletes-just-that-task
  (let [before (seed-family! 0)]
    (is (= 4 (count before)))
    (is (= {:exit 0 :out "Deleted task 3.\n" :err ""} (run "rm" "3" "--force")))
    (is (= (without-family before) (rows)))))

(deftest rm-on-an-unknown-id-fails-and-deletes-nothing
  (let [before (seed-family! 2)]
    (is (= {:exit 1 :out "" :err "Task 99 not found.\n"} (run "rm" "99")))
    (is (= {:exit 1 :out "" :err "Task 99 not found.\n"} (run "rm" "99" "--force")))
    (is (= {:exit 1 :out "" :err "Task 99 not found.\n"} (run "rm" "--force" "99")))
    (is (= before (rows)))))

(deftest every-command-that-takes-an-id-says-not-found-for-a-missing-task
  (insert! {})
  (let [before (rows)]
    (doseq [argv [["start" "99"] ["done" "99"] ["drop" "99"] ["reopen" "99"]
                  ["edit" "99" "--title" "x"] ["rm" "99"]]]
      (testing (pr-str argv)
        (is (= {:exit 1 :out "" :err "Task 99 not found.\n"} (apply run argv)))))
    (is (= before (rows)))))

(defn- seed-day!
  "Tasks around 2026-09-20: done on it (1), dropped on it (2), open across it
  (3), created after it (4), finished before it (5), a subtask of 1 done on it
  (6) and a subtask of 3 still open (7). No task has a due date."
  []
  (let [d #(LocalDate/of 2026 9 %)
        t #(LocalDateTime/of 2026 9 %1 %2 0 0)]
    (insert! {:title "Ship release notes" :status :done :finish-date (d 20) :created-at (t 15 9)})
    (insert! {:title "Old spike" :status :dropped :finish-date (d 20) :created-at (t 10 9)})
    (insert! {:title "Write design" :status :doing :created-at (t 12 9)})
    (insert! {:title "Created later" :created-at (t 22 9)})
    (insert! {:title "Finished earlier" :status :done :finish-date (d 18) :created-at (t 10 9)})
    (insert! {:title "Fix login" :parent-id 1 :status :done :finish-date (d 20) :created-at (t 16 9)})
    (insert! {:title "Outline" :parent-id 3 :created-at (t 13 9)})))

(deftest day-prints-the-date-then-the-done-dropped-and-open-sections
  (seed-day!)
  (is (= {:exit 0
          :out  (str "2026-09-20\n"
                     "Done\n"
                     "  [x] 1 Ship release notes\n"
                     "  [x] 6 Fix login ↳ #1\n"
                     "Dropped\n"
                     "  [-] 2 Old spike\n"
                     "Open\n"
                     "  [~] 3 Write design\n"
                     "  [ ] 7 Outline ↳ #3\n")
          :err  ""}
         (run "day" "2026-09-20"))))

(deftest day-omits-the-sections-that-are-empty
  (insert! {:title "Old spike" :status :dropped :finish-date (LocalDate/of 2026 9 20)
            :created-at (LocalDateTime/of 2026 9 10 9 0 0)})
  (is (= {:exit 0 :out "2026-09-20\nDropped\n  [-] 1 Old spike\n" :err ""}
         (run "day" "2026-09-20"))))

(deftest day-never-lists-a-task-finished-on-the-day-under-open
  (insert! {:title "Ship release notes" :status :done :finish-date (LocalDate/of 2026 9 20)
            :created-at (LocalDateTime/of 2026 9 15 9 0 0)})
  (let [{:keys [out]} (run "day" "2026-09-20")]
    (is (= "2026-09-20\nDone\n  [x] 1 Ship release notes\n" out))
    (is (not (str/includes? out "Open")))))

(deftest day-lists-a-task-created-in-the-last-second-of-the-day-as-open
  (insert! {:title "Late task" :created-at (LocalDateTime/of 2026 9 20 23 59 59)})
  (insert! {:title "Next day task" :created-at (LocalDateTime/of 2026 9 21 0 0 0)})
  (is (= {:exit 0 :out "2026-09-20\nOpen\n  [ ] 1 Late task\n" :err ""}
         (run "day" "2026-09-20")))
  (testing "the next day has both, since neither is finished"
    (is (= {:exit 0 :out "2026-09-21\nOpen\n  [ ] 1 Late task\n  [ ] 2 Next day task\n" :err ""}
           (run "day" "2026-09-21")))))

(deftest day-with-nothing-recorded-says-so-and-exits-zero
  (is (= {:exit 0 :out "Nothing recorded for 2026-09-20.\n" :err ""}
         (run "day" "2026-09-20"))))

(deftest day-says-nothing-was-recorded-when-every-task-falls-outside-the-day
  (insert! {:title "Created later" :created-at (LocalDateTime/of 2026 9 22 9 0 0)})
  (insert! {:title "Finished earlier" :status :done :finish-date (LocalDate/of 2026 9 18)
            :created-at (LocalDateTime/of 2026 9 10 9 0 0)})
  (is (= {:exit 0 :out "Nothing recorded for 2026-09-20.\n" :err ""}
         (run "day" "2026-09-20"))))

(deftest day-reads-a-task-reopened-after-its-finish-day-as-open-on-that-day
  (insert! {:title "Ship release notes" :created-at (LocalDateTime/of 2026 9 15 9 0 0)})
  (is (= {:exit 0 :out "Task 1 marked done.\n" :err ""}
         (run-on (LocalDateTime/of 2026 9 20 9 0 0) "done" "1")))
  (is (= {:exit 0 :out "2026-09-20\nDone\n  [x] 1 Ship release notes\n" :err ""}
         (run "day" "2026-09-20")))
  (is (= {:exit 0 :out "Task 1 reopened.\n" :err ""}
         (run-on (LocalDateTime/of 2026 9 22 9 0 0) "reopen" "1")))
  (is (= {:exit 0 :out "2026-09-20\nOpen\n  [ ] 1 Ship release notes\n" :err ""}
         (run "day" "2026-09-20"))))

(deftest day-lists-a-task-finished-after-the-day-as-open-with-the-open-glyph
  (insert! {:title "Ship release notes" :status :done :finish-date (LocalDate/of 2026 9 22)
            :due-date (LocalDate/of 2026 9 19) :created-at (LocalDateTime/of 2026 9 15 9 0 0)})
  (insert! {:title "Old spike" :status :dropped :finish-date (LocalDate/of 2026 9 23)
            :created-at (LocalDateTime/of 2026 9 10 9 0 0)})
  (is (= {:exit 0 :out "2026-09-20\nOpen\n  [ ] 1 Ship release notes\n  [ ] 2 Old spike\n" :err ""}
         (run "day" "2026-09-20"))))

(deftest day-prints-no-due-label-for-a-task-with-a-due-date
  (insert! {:title "Write design" :status :doing :due-date (LocalDate/of 2026 9 19)
            :created-at (LocalDateTime/of 2026 9 12 9 0 0)})
  (is (= {:exit 0 :out "2026-09-20\nOpen\n  [~] 1 Write design\n" :err ""}
         (run "day" "2026-09-20"))))

(deftest day-without-a-date-shows-today
  (insert! {:title "Done today" :status :done :finish-date (LocalDate/of 2026 9 28)
            :created-at (LocalDateTime/of 2026 9 28 8 0 0)})
  (insert! {:title "Done yesterday" :status :done :finish-date (LocalDate/of 2026 9 27)
            :created-at (LocalDateTime/of 2026 9 27 8 0 0)})
  (is (= {:exit 0 :out "2026-09-28\nDone\n  [x] 1 Done today\n" :err ""}
         (run "day"))))

(deftest day-reads-a-negative-offset-as-a-date
  (insert! {:title "Done today" :status :done :finish-date (LocalDate/of 2026 9 28)
            :created-at (LocalDateTime/of 2026 9 28 8 0 0)})
  (insert! {:title "Done yesterday" :status :done :finish-date (LocalDate/of 2026 9 27)
            :created-at (LocalDateTime/of 2026 9 27 8 0 0)})
  (is (= {:exit 0 :out "2026-09-27\nDone\n  [x] 2 Done yesterday\n" :err ""}
         (run "day" "-1d"))))

(deftest day-with-an-unreadable-date-fails
  (is (= {:exit 1
          :out  ""
          :err  "Can't read date 'fri'. Use YYYY-MM-DD, today, tomorrow, yesterday, +Nd or -Nd.\n"}
         (run "day" "fri"))))

(def ^:private empty-search
  {:exit 1 :out "" :err "Search needs at least one word.\n"})

(deftest search-finds-a-task-by-the-prefix-of-every-quoted-word
  (insert! {:title "Authentication service"})
  (insert! {:title "Authentication only"})
  (insert! {:title "Billing service"})
  (is (= {:exit 0 :out "[ ] 1 Authentication service\n" :err ""}
         (run "search" "auth serv"))))

(deftest search-finds-a-task-by-a-single-word-prefix
  (insert! {:title "Authentication service"})
  (insert! {:title "Billing service"})
  (is (= {:exit 0 :out "[ ] 1 Authentication service\n" :err ""}
         (run "search" "auth"))))

(deftest search-does-not-match-inside-a-word
  (insert! {:title "Authentication service"})
  (is (= {:exit 0 :out "No matches.\n" :err ""}
         (run "search" "thentication"))))

(deftest search-finds-a-note-written-with-accents-from-the-unaccented-word
  (insert! {:title "Login flow" :note "Autentica\u00e7\u00e3o do servi\u00e7o"})
  (insert! {:title "Billing"})
  (is (= {:exit 0 :out "[ ] 1 Login flow\n" :err ""}
         (run "search" "autenticacao"))))

(deftest search-finds-an-accented-title-from-the-unaccented-word
  (insert! {:title "Vi\u1ec7t Nam trip"})
  (insert! {:title "Billing"})
  (is (= {:exit 0 :out "[ ] 1 Vi\u1ec7t Nam trip\n" :err ""}
         (run "search" "viet"))))

(deftest search-ignores-case
  (insert! {:title "Authentication service"})
  (is (= {:exit 0 :out "[ ] 1 Authentication service\n" :err ""}
         (run "search" "AUTH SERV"))))

(deftest search-prints-one-line-per-match-most-relevant-first
  (insert! {:title "Quarterly planning meeting notes and agenda for everyone"})
  (insert! {:title "Quarterly"})
  (insert! {:title "Billing"})
  (is (= {:exit 0
          :out  (str "[ ] 2 Quarterly\n"
                     "[ ] 1 Quarterly planning meeting notes and agenda for everyone\n")
          :err  ""}
         (run "search" "quarterly"))))

(deftest search-covers-every-status
  (insert! {:title "Auth todo"})
  (insert! {:title "Auth doing" :status :doing})
  (insert! {:title "Auth done" :status :done})
  (insert! {:title "Auth dropped" :status :dropped})
  (let [{:keys [exit out err]} (run "search" "auth")]
    (is (= 0 exit))
    (is (= "" err))
    (is (= #{"[ ] 1 Auth todo" "[~] 2 Auth doing" "[x] 3 Auth done" "[-] 4 Auth dropped"}
           (set (str/split-lines out))))
    (is (= 4 (count (str/split-lines out))))))

(deftest search-marks-a-subtask-with-its-parent
  (insert! {:title "Release plan"})
  (insert! {:title "Authentication fix" :parent-id 1})
  (is (= {:exit 0 :out "[ ] 2 Authentication fix \u21b3 #1\n" :err ""}
         (run "search" "auth"))))

(deftest search-finds-a-subtask-whose-parent-does-not-match
  (insert! {:title "Release plan"})
  (insert! {:title "Authentication fix" :parent-id 1})
  (insert! {:title "Authentication docs"})
  (let [lines (str/split-lines (:out (run "search" "auth")))]
    (is (= #{"[ ] 2 Authentication fix \u21b3 #1" "[ ] 3 Authentication docs"} (set lines)))
    (is (= 2 (count lines)))))

(deftest search-with-no-match-says-so-and-exits-zero
  (insert! {:title "Authentication service"})
  (is (= "[ ] 1 Authentication service\n" (:out (run "search" "auth"))))
  (is (= {:exit 0 :out "No matches.\n" :err ""} (run "search" "billing")))
  (is (= {:exit 0 :out "No matches.\n" :err ""} (run "search" "auth billing"))))

(deftest search-on-an-empty-database-says-no-matches
  (is (= {:exit 0 :out "No matches.\n" :err ""} (run "search" "auth"))))

(deftest search-runs-a-word-that-mixes-punctuation-and-letters
  (insert! {:title "auth.svc rollout"})
  (insert! {:title "Billing"})
  (is (= {:exit 0 :out "[ ] 1 auth.svc rollout\n" :err ""} (run "search" "auth.svc")))
  (is (= {:exit 0 :out "[ ] 1 auth.svc rollout\n" :err ""} (run "search" "auth.svc rollout"))))

(deftest search-runs-a-word-with-a-double-quote
  (insert! {:title "Say \"hi\" to the team"})
  (is (= {:exit 0 :out "[ ] 1 Say \"hi\" to the team\n" :err ""} (run "search" "\"hi\""))))

(deftest search-ignores-punctuation-only-words-beside-real-ones
  (insert! {:title "Authentication service"})
  (is (= {:exit 0 :out "[ ] 1 Authentication service\n" :err ""}
         (run "search" "auth . serv"))))

(deftest search-with-no-letter-or-digit-is-refused-as-an-empty-search
  (insert! {:title "Authentication service"})
  (doseq [argv [["search" "-."] ["search" "-"] ["search" "."] ["search" "..."] ["search" "!!! ???"]
                ["search" "- ."] ["search" "\"\""] ["search" ""] ["search" "   "] ["search"]]]
    (testing (pr-str argv)
      (is (= empty-search (apply run argv))))))

(deftest search-with-unquoted-multi-word-text-is-refused
  (insert! {:title "Authentication service"})
  (is (= (extra-argument "serv") (run "search" "auth" "serv")))
  (is (= (extra-argument "b") (run "search" "a" "b" "c"))))

(deftest search-with-an-unknown-option-is-refused
  (insert! {:title "Authentication service"})
  (is (= (unknown-option "bogus") (run "search" "auth" "--bogus" "x"))))

(deftest search-follows-an-edit-of-the-title-and-the-note
  (is (= {:exit 0 :out "Added task 1.\n" :err ""} (run "add" "Authentication service" "--note" "legacy")))
  (is (= "[ ] 1 Authentication service\n" (:out (run "search" "auth"))))
  (is (= "[ ] 1 Authentication service\n" (:out (run "search" "legacy"))))
  (is (= {:exit 0 :out "Updated task 1.\n" :err ""}
         (run "edit" "1" "--title" "Billing report" "--note" "invoices")))
  (testing "the old words stop matching"
    (is (= {:exit 0 :out "No matches.\n" :err ""} (run "search" "auth")))
    (is (= {:exit 0 :out "No matches.\n" :err ""} (run "search" "legacy"))))
  (testing "the new words match"
    (is (= {:exit 0 :out "[ ] 1 Billing report\n" :err ""} (run "search" "billing")))
    (is (= {:exit 0 :out "[ ] 1 Billing report\n" :err ""} (run "search" "invoices")))))

(deftest search-finds-nothing-after-the-task-is-removed
  (run "add" "Authentication service")
  (run "add" "Authentication docs")
  (is (= "[ ] 1 Authentication service\n[ ] 2 Authentication docs\n"
         (:out (run "search" "auth"))))
  (is (= {:exit 0 :out "Deleted task 1.\n" :err ""} (run "rm" "1")))
  (is (= {:exit 0 :out "[ ] 2 Authentication docs\n" :err ""} (run "search" "auth")))
  (is (= {:exit 0 :out "Deleted task 2.\n" :err ""} (run "rm" "2")))
  (is (= {:exit 0 :out "No matches.\n" :err ""} (run "search" "auth"))))

(deftest search-finds-nothing-after-a-forced-remove-of-a-parent-and-its-subtasks
  (run "add" "Authentication service")
  (run "add" "Authentication step" "--parent" "1")
  (is (= {:exit 0 :out "Deleted task 1 and 1 subtask.\n" :err ""} (run "rm" "1" "--force")))
  (is (= {:exit 0 :out "No matches.\n" :err ""} (run "search" "auth"))))

(deftest search-still-finds-a-task-after-it-is-marked-done
  (run "add" "Authentication service")
  (is (= {:exit 0 :out "Task 1 marked done.\n" :err ""} (run "done" "1")))
  (is (= {:exit 0 :out "[x] 1 Authentication service\n" :err ""} (run "search" "auth serv"))))
