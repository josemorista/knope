(ns knope.adapters.task-test
  (:require [clojure.test :refer [deftest is testing]]
            [knope.adapters.task :as adapters.task]
            [knope.gen.task :as gen.task])
  (:import (java.time LocalDate LocalDateTime)))

(def today (LocalDate/of 2026 9 28))

(defn- without-parent
  "Parent handling arrives in task 4.0; these tests pin the other keys."
  [attrs]
  (dissoc attrs :parent-id))

(defn- param-values [params]
  (if (map? params) (vals params) params))

(deftest args->new-attrs-builds-attributes-from-argv-options
  (is (= {:title "Write report" :note nil :due-date (LocalDate/of 2026 9 29)}
         (without-parent
          (adapters.task/args->new-attrs {:title "Write report" :due "tomorrow"} today)))))

(deftest args->new-attrs-defaults-optional-values-to-nil
  (is (= {:title "x" :note nil :due-date nil}
         (without-parent (adapters.task/args->new-attrs {:title "x"} today)))))

(deftest args->new-attrs-keeps-a-note
  (is (= {:title "x" :note "for Q3" :due-date nil}
         (without-parent (adapters.task/args->new-attrs {:title "x" :note "for Q3"} today)))))

(deftest args->new-attrs-turns-an-empty-note-into-nil
  (is (= {:title "x" :note nil :due-date nil}
         (without-parent (adapters.task/args->new-attrs {:title "x" :note ""} today)))))

(deftest args->new-attrs-resolves-every-due-date-form-against-today
  (doseq [[input expected] {"2026-10-05" (LocalDate/of 2026 10 5)
                            "today"      today
                            "yesterday"  (LocalDate/of 2026 9 27)
                            "+3d"        (LocalDate/of 2026 10 1)
                            "-2d"        (LocalDate/of 2026 9 26)}]
    (testing input
      (is (= expected
             (:due-date (adapters.task/args->new-attrs {:title "x" :due input} today)))))))

(deftest args->new-attrs-reports-a-missing-title
  (doseq [opts [{} {:title nil} {:title ""} {:title "   "} {:due "tomorrow"}]]
    (testing (pr-str opts)
      (is (= :validation/missing-title
             (:error/code (adapters.task/args->new-attrs opts today)))))))

(deftest args->new-attrs-reports-a-bad-date
  (let [result (adapters.task/args->new-attrs {:title "x" :due "fri"} today)]
    (is (= :validation/bad-date (:error/code result)))
    (is (map? (:error/details result)))
    (is (some #(= "fri" %) (vals (:error/details result))))))

(deftest args->changes-builds-only-the-fields-that-were-given
  (is (= {:title "new name"} (adapters.task/args->changes {:title "new name"} today)))
  (is (= {:due-date (LocalDate/of 2026 9 29)} (adapters.task/args->changes {:due "tomorrow"} today)))
  (is (= {:note "buy milk"} (adapters.task/args->changes {:note "buy milk"} today))))

(deftest args->changes-turns-an-empty-note-into-a-nil-note
  (is (= {:note nil} (adapters.task/args->changes {:note ""} today))))

(deftest args->changes-turns-clear-due-into-a-nil-due-date
  (is (= {:due-date nil} (adapters.task/args->changes {:clear-due true} today))))

(deftest args->changes-combines-several-options
  (is (= {:title "x" :note nil :due-date nil}
         (adapters.task/args->changes {:title "x" :note "" :clear-due true} today)))
  (is (= {:title "x" :note "n" :due-date (LocalDate/of 2026 10 1)}
         (adapters.task/args->changes {:title "x" :note "n" :due "+3d"} today))))

(deftest args->changes-reports-nothing-to-edit
  (doseq [opts [{} {:clear-due false}]]
    (testing (pr-str opts)
      (is (= {:error/code :validation/nothing-to-edit :error/details {}}
             (adapters.task/args->changes opts today))))))

(deftest args->changes-reports-conflicting-flags
  (is (= {:error/code :validation/conflicting-flags :error/details {}}
         (adapters.task/args->changes {:due "tomorrow" :clear-due true :title "x"} today))))

(deftest args->changes-reports-a-blank-title
  (doseq [title ["" "   "]]
    (testing (pr-str title)
      (is (= {:error/code :validation/missing-title :error/details {}}
             (adapters.task/args->changes {:title title} today))))))

(deftest args->changes-reports-a-bad-date
  (is (= {:error/code :validation/bad-date :error/details {:input "fri"}}
         (adapters.task/args->changes {:due "fri"} today))))

(deftest row->task-builds-a-task-from-a-snake-case-row
  (is (= {:id          4
          :parent-id   2
          :title       "Write report"
          :note        "for Q3"
          :status      :todo
          :created-at  (LocalDateTime/of 2026 9 28 12 0 0)
          :due-date    (LocalDate/of 2026 10 1)
          :finish-date nil}
         (adapters.task/row->task {:id          4
                                   :parent_id   2
                                   :title       "Write report"
                                   :note        "for Q3"
                                   :status      "todo"
                                   :created_at  "2026-09-28T12:00:00"
                                   :due_date    "2026-10-01"
                                   :finish_date nil}))))

(deftest row->task-reads-a-closed-row-with-nil-optionals
  (is (= {:id          9
          :parent-id   nil
          :title       "Ship it"
          :note        nil
          :status      :done
          :created-at  (LocalDateTime/of 2026 9 20 8 30 45)
          :due-date    nil
          :finish-date (LocalDate/of 2026 9 27)}
         (adapters.task/row->task {:id          9
                                   :parent_id   nil
                                   :title       "Ship it"
                                   :note        nil
                                   :status      "done"
                                   :created_at  "2026-09-20T08:30:45"
                                   :due_date    nil
                                   :finish_date "2026-09-27"}))))

(deftest task->params-writes-created-at-in-the-fixed-format
  (let [task   (gen.task/task {:created-at (LocalDateTime/of 2026 9 28 12 0 0)
                               :due-date   (LocalDate/of 2026 10 1)})
        values (set (param-values (adapters.task/task->params task)))]
    (testing "zero seconds are kept, unlike LocalDateTime/toString"
      (is (contains? values "2026-09-28T12:00:00")))
    (is (contains? values "2026-10-01"))
    (is (contains? values "todo"))
    (is (contains? values "Write report"))))

(deftest task->params-holds-only-values-the-pod-can-bind
  (let [task (gen.task/task {:status :done :note "n" :due-date (LocalDate/of 2026 10 1)})]
    (is (every? #(or (nil? %) (string? %) (integer? %))
                (param-values (adapters.task/task->params task))))))

(deftest task->display-row-picks-a-glyph-per-status
  (doseq [[status glyph] {:todo "[ ]" :doing "[~]" :done "[x]" :dropped "[-]"}]
    (testing status
      (is (= {:glyph glyph :id 1 :title "Write report" :due-label nil :urgency nil}
             (adapters.task/task->display-row (gen.task/task {:status status}) today {}))))))

(deftest task->display-row-carries-id-and-title
  (is (= {:glyph "[ ]" :id 4 :title "Ship it" :due-label nil :urgency nil}
         (adapters.task/task->display-row (gen.task/task {:id 4 :title "Ship it"}) today {}))))

(deftest task->display-row-labels-the-due-date-with-its-urgency
  (doseq [[due text urgency] [[(LocalDate/of 2026 9 26) "2d overdue" :overdue]
                              [(LocalDate/of 2026 9 27) "1d overdue" :overdue]
                              [today "today" :soon]
                              [(LocalDate/of 2026 9 29) "tomorrow" :soon]
                              [(LocalDate/of 2026 10 1) "in 3d" :later]
                              [(LocalDate/of 2026 10 6) "2026-10-06" :later]]]
    (testing text
      (is (= {:glyph "[ ]" :id 1 :title "Write report" :due-label text :urgency urgency}
             (adapters.task/task->display-row (gen.task/task {:due-date due}) today {}))))))

(deftest task->display-row-shows-no-due-label-for-an-undated-task
  (is (= {:glyph "[ ]" :id 1 :title "Write report" :due-label nil :urgency nil}
         (adapters.task/task->display-row (gen.task/task {:due-date nil}) today {}))))

(deftest parse-page-defaults-to-the-first-page
  (is (= 0 (adapters.task/parse-page nil))))

(deftest parse-page-reads-a-whole-number-from-zero
  (doseq [[input page] [["0" 0] ["1" 1] ["12" 12]]]
    (testing input
      (is (= page (adapters.task/parse-page input))))))

(deftest parse-page-reports-anything-else
  (doseq [input ["-1" "abc" "1.5" "" "99999999999999999999"]]
    (testing input
      (is (= {:error/code :validation/bad-page :error/details {:input input}}
             (adapters.task/parse-page input))))))

(deftest parse-id-reads-a-positive-integer
  (doseq [[input id] [["12" 12] ["1" 1]]]
    (testing input
      (is (= id (adapters.task/parse-id input))))))

(deftest parse-id-reports-anything-else-with-the-raw-value
  (doseq [input ["abc" "0" "-1" "" "1.5" " 12" "99999999999999999999"]]
    (testing (pr-str input)
      (is (= {:error/code :validation/bad-id :error/details {:input input}}
             (adapters.task/parse-id input))))))

(deftest args->new-attrs-reads-the-parent-id
  (is (= {:title "step" :note nil :due-date nil :parent-id 3}
         (adapters.task/args->new-attrs {:title "step" :parent "3"} today))))

(deftest args->new-attrs-has-no-parent-by-default
  (is (= {:title "x" :note nil :due-date nil :parent-id nil}
         (adapters.task/args->new-attrs {:title "x"} today))))

(deftest args->new-attrs-reports-a-bad-parent-id
  (doseq [input ["abc" "0" "-1"]]
    (testing input
      (is (= {:error/code :validation/bad-id :error/details {:input input}}
             (adapters.task/args->new-attrs {:title "x" :parent input} today))))))

(deftest task->display-row-renders-progress-as-done-over-planned
  (is (= {:glyph "[ ]" :id 1 :title "Write report" :due-label nil :urgency nil :progress "3/5"}
         (adapters.task/task->display-row
          (gen.task/task) today {:progress {:done 3 :planned 5}}))))

(deftest task->display-row-has-no-progress-text-without-progress
  (is (= {:glyph "[ ]" :id 1 :title "Write report" :due-label nil :urgency nil}
         (adapters.task/task->display-row (gen.task/task) today {:progress nil}))))

(deftest task->display-row-flags-a-dimmed-context-row
  (is (= {:glyph "[x]" :id 1 :title "Write report" :due-label nil :urgency nil :dimmed? true}
         (adapters.task/task->display-row
          (gen.task/task {:status :done}) today {:dimmed? true}))))

(deftest task->display-row-writes-a-parent-reference
  (is (= {:glyph "[ ]" :id 13 :title "Write report" :due-label nil :urgency nil :parent-ref "↳ #12"}
         (adapters.task/task->display-row
          (gen.task/task {:id 13 :parent-id 12}) today {:parent-ref 12}))))

(deftest search-match-turns-each-word-into-a-quoted-prefix
  (is (= "\"auth\"* \"serv\"*" (adapters.task/search-match "auth serv")))
  (is (= "\"quarterly\"*" (adapters.task/search-match "quarterly"))))

(deftest search-match-splits-on-any-run-of-whitespace
  (is (= "\"auth\"* \"serv\"*" (adapters.task/search-match "  auth \t  serv\n"))))

(deftest search-match-keeps-accented-letters-and-digits
  (is (= "\"autenticação\"* \"2026\"*" (adapters.task/search-match "autenticação 2026"))))

(deftest search-match-drops-a-lone-dash
  (is (= "\"auth\"*" (adapters.task/search-match "- auth")))
  (is (= "\"auth\"* \"serv\"*" (adapters.task/search-match "auth - serv"))))

(deftest search-match-drops-a-lone-dot
  (is (= "\"auth\"*" (adapters.task/search-match ". auth")))
  (is (= "\"auth\"* \"serv\"*" (adapters.task/search-match "auth . serv"))))

(deftest search-match-drops-a-lone-double-quote
  (is (= "\"auth\"*" (adapters.task/search-match "\" auth")))
  (is (= "\"auth\"*" (adapters.task/search-match "auth \""))))

(deftest search-match-keeps-a-word-that-mixes-punctuation-and-letters
  (is (= "\"auth.svc\"*" (adapters.task/search-match "auth.svc")))
  (is (= "\"auth.svc\"* \"v2\"*" (adapters.task/search-match "auth.svc v2"))))

(deftest search-match-keeps-a-word-that-mixes-punctuation-and-digits
  (is (= "\"-1\"*" (adapters.task/search-match "-1"))))

(deftest search-match-doubles-a-double-quote-inside-a-word
  (is (= "\"say\"\"hi\"*" (adapters.task/search-match "say\"hi")))
  (is (= "\"\"\"hi\"\"\"*" (adapters.task/search-match "\"hi\""))))

(deftest search-match-reports-an-empty-search-when-no-word-has-a-letter-or-digit
  (doseq [input ["" "   " "-" "." "-." "..." "- . \"" "!!! ???" "\"\""]]
    (testing (pr-str input)
      (is (= {:error/code :validation/empty-search :error/details {}}
             (adapters.task/search-match input))))))

(defn- detail
  "The display map of task with the given parent, children and progress."
  ([task] (detail task {}))
  ([task extra]
   (adapters.task/task->detail (merge {:task task :parent nil :children [] :progress nil} extra)
                               today)))

(deftest task->detail-builds-the-display-map-of-a-parent
  (let [parent (gen.task/task {:id 1 :title "Plan the harvest festival" :note "Ask Ron about the permit."
                               :due-date (LocalDate/of 2026 10 1)})
        step   (gen.task/task {:id 2 :title "Book the venue" :parent-id 1 :status :doing
                               :due-date (LocalDate/of 2026 9 30)})]
    (is (= {:id       1
            :title    "Plan the harvest festival"
            :status   "[ ] todo"
            :parent   nil
            :due      {:text "in 3d (2026-10-01)" :urgency :later}
            :created  "2026-09-28"
            :finished nil
            :note     "Ask Ron about the permit."
            :subtasks {:progress "0/1"
                       :rows     [{:glyph "[~]" :id 2 :title "Book the venue"
                                   :due-label "in 2d" :urgency :later}]}}
           (detail parent {:children [step] :progress {:done 0 :planned 1}})))))

(deftest task->detail-leaves-out-every-field-a-bare-task-does-not-have
  (is (= {:id       1
          :title    "Write report"
          :status   "[ ] todo"
          :parent   nil
          :due      nil
          :created  "2026-09-28"
          :finished nil
          :note     nil
          :subtasks nil}
         (detail (gen.task/task)))))

(deftest task->detail-writes-the-glyph-and-the-status-word
  (doseq [[status status-text] {:todo "[ ] todo" :doing "[~] doing" :done "[x] done" :dropped "[-] dropped"}]
    (testing status
      (is (= status-text (:status (detail (gen.task/task {:status status}))))))))

(deftest task->detail-writes-the-due-label-followed-by-the-date
  (doseq [[due text urgency] [[(LocalDate/of 2026 9 26) "2d overdue (2026-09-26)" :overdue]
                              [today "today (2026-09-28)" :soon]
                              [(LocalDate/of 2026 9 29) "tomorrow (2026-09-29)" :soon]
                              [(LocalDate/of 2026 10 1) "in 3d (2026-10-01)" :later]
                              [(LocalDate/of 2026 10 5) "in 7d (2026-10-05)" :later]]]
    (testing text
      (is (= {:text text :urgency urgency}
             (:due (detail (gen.task/task {:due-date due}))))))))

(deftest task->detail-writes-a-due-date-more-than-a-week-away-once
  (is (= {:text "2026-10-06" :urgency :later}
         (:due (detail (gen.task/task {:due-date (LocalDate/of 2026 10 6)}))))))

(deftest task->detail-has-no-due-for-a-task-without-a-due-date
  (is (nil? (:due (detail (gen.task/task {:due-date nil}))))))

(deftest task->detail-has-no-note-when-it-is-empty-or-only-whitespace
  (doseq [note [nil "" "   " "\n \t\n"]]
    (testing (pr-str note)
      (is (nil? (:note (detail (gen.task/task {:note note}))))))))

(deftest task->detail-keeps-a-multi-line-note-as-written
  (is (= "First line.\nSecond line."
         (:note (detail (gen.task/task {:note "First line.\nSecond line."}))))))

(deftest task->detail-writes-the-date-of-creation-without-the-time
  (is (= "2026-09-20"
         (:created (detail (gen.task/task {:created-at (LocalDateTime/of 2026 9 20 8 30 45)}))))))

(deftest task->detail-writes-the-finish-date-of-a-closed-task
  (doseq [status [:done :dropped]]
    (testing status
      (is (= "2026-09-27"
             (:finished (detail (gen.task/task {:status status :finish-date (LocalDate/of 2026 9 27)}))))))))

(deftest task->detail-has-no-finish-date-for-an-open-task
  (is (nil? (:finished (detail (gen.task/task {:status :doing}))))))

(deftest task->detail-writes-the-parent-as-its-id-and-title
  (let [parent (gen.task/task {:id 1 :title "Plan the harvest festival"})
        step   (gen.task/task {:id 2 :title "Book the venue" :parent-id 1})]
    (is (= "#1 Plan the harvest festival" (:parent (detail step {:parent parent}))))
    (is (nil? (:subtasks (detail step {:parent parent}))))))

(deftest task->detail-writes-only-the-parent-id-when-the-parent-was-not-found
  (is (= "#7" (:parent (detail (gen.task/task {:id 2 :parent-id 7}) {:parent nil})))))

(deftest task->detail-writes-the-subtask-progress-as-done-over-planned
  (let [parent (gen.task/task {:id 1})
        done   (gen.task/task {:id 2 :parent-id 1 :status :done})
        open   (gen.task/task {:id 3 :parent-id 1})]
    (is (= {:progress "1/2"
            :rows     [{:glyph "[ ]" :id 3 :title "Write report" :due-label nil :urgency nil}
                       {:glyph "[x]" :id 2 :title "Write report" :due-label nil :urgency nil}]}
           (:subtasks (detail parent {:children [open done] :progress {:done 1 :planned 2}}))))))

(deftest task->detail-has-no-progress-when-every-subtask-is-dropped
  (let [parent  (gen.task/task {:id 1})
        dropped (gen.task/task {:id 2 :parent-id 1 :status :dropped})]
    (is (= {:progress nil
            :rows     [{:glyph "[-]" :id 2 :title "Write report" :due-label nil :urgency nil}]}
           (:subtasks (detail parent {:children [dropped] :progress nil}))))))
