(ns gaoshun.adapters.task-test
  (:require [clojure.test :refer [deftest is testing]]
            [gaoshun.adapters.task :as adapters.task]
            [gaoshun.gen.task :as gen.task])
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
