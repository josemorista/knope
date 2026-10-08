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

(defn- tasks-of
  "The tasks of a build-tree result, in order. Entry shape is not fixed by the
  spec beyond leaving room for :children and :context?, so accept either a
  wrapper holding :task or the task itself."
  [tree]
  (mapv #(if (contains? % :task) (:task %) (dissoc % :children :context?)) tree))

(defn- ids-of [tree] (mapv :id (tasks-of tree)))

(deftest build-tree-of-nothing-is-empty
  (is (empty? (logic.task/build-tree [] {})))
  (is (empty? (logic.task/build-tree [] {:all? true}))))

(deftest build-tree-without-all-keeps-only-todo-and-doing-tasks
  (let [todo    (gen.task/task {:id 1 :status :todo})
        doing   (gen.task/task {:id 2 :status :doing})
        done    (gen.task/task {:id 3 :status :done})
        dropped (gen.task/task {:id 4 :status :dropped})]
    (is (= [todo doing]
           (tasks-of (logic.task/build-tree [dropped done doing todo] {}))))
    (is (= [todo doing]
           (tasks-of (logic.task/build-tree [dropped done doing todo] {:all? false}))))))

(deftest build-tree-with-all-keeps-every-task
  (let [tasks (mapv (fn [id status] (gen.task/task {:id id :status status}))
                    [1 2 3 4]
                    [:todo :doing :done :dropped])]
    (is (= (set tasks)
           (set (tasks-of (logic.task/build-tree (reverse tasks) {:all? true})))))))

(deftest build-tree-sorts-open-tasks-by-due-date-then-id-with-undated-last
  (let [tasks [(gen.task/task {:id 1 :due-date (LocalDate/of 2026 10 5)})
               (gen.task/task {:id 2 :due-date nil})
               (gen.task/task {:id 3 :due-date (LocalDate/of 2026 9 29)})
               (gen.task/task {:id 4 :due-date (LocalDate/of 2026 9 29)})
               (gen.task/task {:id 5 :due-date nil})
               (gen.task/task {:id 6 :due-date (LocalDate/of 2026 9 20)})]]
    (is (= [6 3 4 1 2 5] (ids-of (logic.task/build-tree tasks {}))))
    (is (= [6 3 4 1 2 5] (ids-of (logic.task/build-tree (reverse tasks) {}))))))

(deftest build-tree-sorts-todo-and-doing-together-by-due-date
  (let [tasks [(gen.task/task {:id 1 :status :todo :due-date (LocalDate/of 2026 10 2)})
               (gen.task/task {:id 2 :status :doing :due-date (LocalDate/of 2026 10 1)})
               (gen.task/task {:id 3 :status :todo :due-date (LocalDate/of 2026 10 1)})]]
    (is (= [2 3 1] (ids-of (logic.task/build-tree tasks {}))))))

(deftest build-tree-with-all-puts-open-before-closed
  (let [tasks [(gen.task/task {:id 1 :status :done :finish-date (LocalDate/of 2026 9 28)})
               (gen.task/task {:id 2 :status :todo :due-date nil})
               (gen.task/task {:id 3 :status :dropped :finish-date (LocalDate/of 2026 9 28)})
               (gen.task/task {:id 4 :status :doing :due-date (LocalDate/of 2026 12 1)})]]
    (is (= [4 2 3 1] (ids-of (logic.task/build-tree tasks {:all? true}))))))

(deftest build-tree-sorts-closed-tasks-by-finish-date-descending-then-id-descending
  (let [tasks [(gen.task/task {:id 1 :status :done :finish-date (LocalDate/of 2026 9 27)})
               (gen.task/task {:id 2 :status :done :finish-date (LocalDate/of 2026 9 27)})
               (gen.task/task {:id 3 :status :done :finish-date (LocalDate/of 2026 9 20)})
               (gen.task/task {:id 4 :status :dropped :finish-date (LocalDate/of 2026 9 28)})]]
    (is (= [4 2 1 3] (ids-of (logic.task/build-tree tasks {:all? true}))))
    (is (= [4 2 1 3] (ids-of (logic.task/build-tree (reverse tasks) {:all? true}))))))
