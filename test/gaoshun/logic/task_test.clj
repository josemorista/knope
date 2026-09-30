(ns gaoshun.logic.task-test
  (:require [clojure.test :refer [deftest is testing]]
            [gaoshun.gen.task :as gen.task]
            [gaoshun.logic.task :as logic.task])
  (:import (java.time LocalDate LocalDateTime)))

(def now (LocalDateTime/of 2026 9 28 12 0 0))
(def today (LocalDate/of 2026 9 28))

(defn- invariant-violation? [f]
  (try
    (f)
    false
    (catch clojure.lang.ExceptionInfo e
      (= :gaoshun/invariant (:type (ex-data e))))))

(def valid-attrs
  {:id          7
   :parent-id   3
   :title       "Write report"
   :note        "for Q3"
   :status      :doing
   :created-at  now
   :due-date    (LocalDate/of 2026 10 1)
   :finish-date nil})

(deftest make-task-returns-the-task-map
  (is (= valid-attrs (logic.task/make-task valid-attrs))))

(deftest make-task-accepts-an-unsaved-task
  (let [attrs (assoc valid-attrs :id nil :parent-id nil :note nil :due-date nil :status :todo)]
    (is (= attrs (logic.task/make-task attrs)))))

(deftest make-task-accepts-closed-tasks-with-a-finish-date
  (doseq [status [:done :dropped]]
    (testing status
      (let [attrs (assoc valid-attrs :status status :finish-date today)]
        (is (= attrs (logic.task/make-task attrs)))))))

(deftest make-task-truncates-created-at-to-seconds
  (is (= now (:created-at (logic.task/make-task
                           (assoc valid-attrs :created-at (.withNano now 123456789)))))))

(deftest make-task-rejects-an-unknown-status
  (is (invariant-violation? #(logic.task/make-task (assoc valid-attrs :status :bogus))))
  (is (invariant-violation? #(logic.task/make-task (assoc valid-attrs :status "todo"))))
  (is (invariant-violation? #(logic.task/make-task (assoc valid-attrs :status nil)))))

(deftest make-task-rejects-a-blank-title
  (doseq [title ["" "   " nil]]
    (testing (pr-str title)
      (is (invariant-violation? #(logic.task/make-task (assoc valid-attrs :title title)))))))

(deftest make-task-rejects-a-finish-date-on-an-open-task
  (doseq [status [:todo :doing]]
    (testing status
      (is (invariant-violation?
           #(logic.task/make-task (assoc valid-attrs :status status :finish-date today)))))))

(deftest make-task-rejects-a-closed-task-without-a-finish-date
  (doseq [status [:done :dropped]]
    (testing status
      (is (invariant-violation?
           #(logic.task/make-task (assoc valid-attrs :status status :finish-date nil)))))))

(deftest generator-builds-valid-tasks-for-every-status
  (doseq [status [:todo :doing :done :dropped]]
    (testing status
      (let [t (gen.task/task {:status status})]
        (is (= status (:status t)))
        (is (= (contains? #{:done :dropped} status) (some? (:finish-date t))))))))

(deftest new-task-creates-a-todo-created-now
  (is (= {:id          nil
          :parent-id   nil
          :title       "Write report"
          :note        nil
          :status      :todo
          :created-at  now
          :due-date    (LocalDate/of 2026 9 29)
          :finish-date nil}
         (logic.task/new-task {:parent-id nil
                               :title     "Write report"
                               :note      nil
                               :due-date  (LocalDate/of 2026 9 29)}
                              now))))

(deftest new-task-keeps-parent-and-note
  (is (= {:id          nil
          :parent-id   3
          :title       "Sub"
          :note        "details"
          :status      :todo
          :created-at  now
          :due-date    nil
          :finish-date nil}
         (logic.task/new-task {:parent-id 3 :title "Sub" :note "details" :due-date nil} now))))

(deftest new-task-truncates-the-clock-to-seconds
  (is (= now (:created-at (logic.task/new-task {:parent-id nil :title "x" :note nil :due-date nil}
                                                (.withNano now 999999999))))))

(deftest new-task-rejects-a-blank-title
  (is (invariant-violation?
       #(logic.task/new-task {:parent-id nil :title " " :note nil :due-date nil} now))))
