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
    (is (= "More on page 1: gaoshun list --page 1" (last lines)))))

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
    (is (= "More on page 1: gaoshun list --all --page 1" (last lines))))
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
    (is (= "More on page 1: gaoshun list --page 1" (last lines))))
  (is (= {:exit 0 :out "[ ] 51 Task 51\n" :err ""} (run "list" "--page" "1"))))

(deftest list-does-not-leak-the-subtasks-of-the-next-page-root
  (add-open-tasks! 51)
  (insert! {:title "Step of 51" :parent-id 51})
  (let [lines (str/split-lines (:out (run "list")))]
    (is (= 51 (count lines)))
    (is (not-any? #(str/includes? % "Step of 51") lines))
    (is (= "More on page 1: gaoshun list --page 1" (last lines))))
  (is (= "[ ] 51 Task 51  0/1\n  [ ] 52 Step of 51\n" (:out (run "list" "--page" "1")))))

(deftest list-with-exactly-fifty-roots-and-subtasks-has-no-pointer
  (add-open-tasks! 50)
  (insert! {:title "Step" :parent-id 50})
  (let [lines (str/split-lines (:out (run "list")))]
    (is (= 51 (count lines)))
    (is (re-find #"^  \[ \] 51 " (last lines)))))

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
  (is (= ["buy milk" "x"] (map :title (rows))))
  (is (= [nil "buy milk"] (map :note (rows)))))

(deftest list-with-an-extra-word-is-refused
  (insert! {:title "Open one"})
  (is (= (extra-argument "extra") (run "list" "extra"))))

(deftest a-transition-with-an-extra-word-is-refused-and-changes-nothing
  (insert! {:title "One"})
  (insert! {:title "Two"})
  (insert! {:title "Three"})
  (doseq [command ["start" "done" "drop" "reopen"]]
    (testing command
      (is (= (extra-argument "4") (run command "3" "4")))))
  (is (= ["todo" "todo" "todo"] (map :status (rows)))))

(deftest add-with-an-unknown-option-is-refused
  (is (= (unknown-option "bogus") (run "add" "x" "--bogus" "y")))
  (is (= (unknown-option "title") (run "add" "--title" "x" "y")))
  (is (empty? (rows))))

(deftest a-transition-with-an-unknown-option-is-refused-and-changes-nothing
  (insert! {:title "One"})
  (insert! {:title "Two"})
  (insert! {:title "Three"})
  (doseq [command ["start" "done" "drop" "reopen"]]
    (testing command
      (is (= (unknown-option "bogus") (run command "--bogus" "x" "3")))))
  (is (= ["todo" "todo" "todo"] (map :status (rows)))))

(deftest list-with-an-unknown-option-is-refused
  (insert! {:title "Open one"})
  (is (= (unknown-option "bogus") (run "list" "--bogus"))))

(deftest only-the-top-level-accepts-help
  (is (= (unknown-option "help") (run "add" "--help")))
  (is (= (unknown-option "help") (run "list" "--help")))
  (is (= 0 (:exit (run "--help")))))

(deftest the-shape-is-checked-before-any-value
  (insert! {:title "One"})
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
  (is (= ["One"] (map :title (rows)))))

(deftest help-prints-the-usage-with-descriptions-in-one-column
  (is (= {:exit 0
          :out  (str "Usage: gaoshun <command> [options]\n"
                     "\n"
                     "Commands:\n"
                     "  add <title> [--due DATE] [--note TEXT] [--parent ID]  Add a task, or a subtask of ID.\n"
                     "  list [--all] [--page N]                               Show pending tasks, 50 per page; --all includes closed ones.\n"
                     "  start ID                                              Mark a task as in progress.\n"
                     "  done ID                                               Mark a task as done.\n"
                     "  drop ID                                               Mark a task as dropped.\n"
                     "  reopen ID                                             Move a done or dropped task back to todo.\n")
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
