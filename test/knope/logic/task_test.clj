(ns knope.logic.task-test
  (:require [clojure.test :refer [deftest is testing]]
            [knope.gen.task :as gen.task]
            [knope.logic.task :as logic.task])
  (:import (java.time LocalDate LocalDateTime)))

(def now (LocalDateTime/of 2026 9 28 12 0 0))
(def today (LocalDate/of 2026 9 28))

(defn- invariant-violation? [f]
  (try
    (f)
    false
    (catch clojure.lang.ExceptionInfo e
      (= :knope/invariant (:type (ex-data e))))))

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

(deftest apply-edit-changes-the-title
  (is (= (assoc valid-attrs :title "new name")
         (logic.task/apply-edit valid-attrs {:title "new name"}))))

(deftest apply-edit-changes-the-due-date
  (is (= (assoc valid-attrs :due-date (LocalDate/of 2026 11 1))
         (logic.task/apply-edit valid-attrs {:due-date (LocalDate/of 2026 11 1)}))))

(deftest apply-edit-clears-the-due-date-and-the-note
  (is (= (assoc valid-attrs :due-date nil)
         (logic.task/apply-edit valid-attrs {:due-date nil})))
  (is (= (assoc valid-attrs :note nil)
         (logic.task/apply-edit valid-attrs {:note nil}))))

(deftest apply-edit-changes-the-note
  (is (= (assoc valid-attrs :note "new")
         (logic.task/apply-edit valid-attrs {:note "new"}))))

(deftest apply-edit-leaves-status-and-finish-date-alone
  (let [done (assoc valid-attrs :status :done :finish-date today)]
    (is (= (assoc done :title "x") (logic.task/apply-edit done {:title "x"})))))

(deftest apply-edit-rejects-a-blank-title
  (is (invariant-violation? #(logic.task/apply-edit valid-attrs {:title " "}))))

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

(deftest parent-error-reports-a-missing-parent
  (let [result (logic.task/parent-error 12 nil)]
    (is (= :task/not-found (:error/code result)))
    (is (some #(= 12 %) (vals (:error/details result))))))

(deftest parent-error-reports-a-parent-that-is-itself-a-subtask
  (let [result (logic.task/parent-error 3 (gen.task/task {:id 3 :parent-id 1}))]
    (is (= :task/nesting-too-deep (:error/code result)))
    (is (some #(= 3 %) (vals (:error/details result))))))

(deftest parent-error-accepts-a-root-task-whatever-its-status
  (doseq [status [:todo :doing :done :dropped]]
    (testing status
      (is (nil? (logic.task/parent-error 1 (gen.task/task {:id 1 :status status})))))))

(deftest progress-counts-done-over-the-children-that-are-not-dropped
  (is (= {:done 2 :planned 4}
         (logic.task/progress [(gen.task/task {:id 2 :parent-id 1 :status :todo})
                               (gen.task/task {:id 3 :parent-id 1 :status :doing})
                               (gen.task/task {:id 4 :parent-id 1 :status :done})
                               (gen.task/task {:id 5 :parent-id 1 :status :done})
                               (gen.task/task {:id 6 :parent-id 1 :status :dropped})]))))

(deftest progress-starts-at-zero-done
  (is (= {:done 0 :planned 1}
         (logic.task/progress [(gen.task/task {:id 2 :parent-id 1 :status :doing})]))))

(deftest progress-is-nil-when-there-is-nothing-planned
  (is (nil? (logic.task/progress [])))
  (is (nil? (logic.task/progress [(gen.task/task {:id 2 :parent-id 1 :status :dropped})
                                  (gen.task/task {:id 3 :parent-id 1 :status :dropped})]))))

(defn- entry-task
  "The task of a build-tree entry, whether children come wrapped or bare."
  [entry]
  (if (contains? entry :task) (:task entry) (dissoc entry :children :context? :progress)))

(defn- child-ids [entry] (mapv (comp :id entry-task) (:children entry)))

(defn- entry-of [tree id] (first (filter #(= id (:id (entry-task %))) tree)))

(deftest build-tree-nests-children-under-their-root
  (let [tasks [(gen.task/task {:id 1 :title "first"})
               (gen.task/task {:id 2 :title "second"})
               (gen.task/task {:id 3 :parent-id 2})
               (gen.task/task {:id 4 :parent-id 1})
               (gen.task/task {:id 5 :parent-id 2})]
        tree  (logic.task/build-tree tasks {})]
    (is (= [1 2] (ids-of tree)))
    (is (= [4] (child-ids (entry-of tree 1))))
    (is (= [3 5] (child-ids (entry-of tree 2))))))

(deftest build-tree-sorts-open-children-by-due-date-then-id-with-undated-last
  (let [tasks [(gen.task/task {:id 1})
               (gen.task/task {:id 2 :parent-id 1})
               (gen.task/task {:id 3 :parent-id 1 :due-date (LocalDate/of 2026 10 5)})
               (gen.task/task {:id 4 :parent-id 1 :due-date (LocalDate/of 2026 9 29)})
               (gen.task/task {:id 5 :parent-id 1 :due-date (LocalDate/of 2026 9 29)})
               (gen.task/task {:id 6 :parent-id 1})]]
    (is (= [4 5 3 2 6] (child-ids (entry-of (logic.task/build-tree tasks {}) 1))))
    (is (= [4 5 3 2 6] (child-ids (entry-of (logic.task/build-tree (reverse tasks) {}) 1))))))

(deftest build-tree-with-all-sorts-children-open-first-then-closed-by-finish-date-descending
  (let [tasks [(gen.task/task {:id 1})
               (gen.task/task {:id 2 :parent-id 1 :status :done :finish-date (LocalDate/of 2026 9 20)})
               (gen.task/task {:id 3 :parent-id 1 :status :done :finish-date (LocalDate/of 2026 9 27)})
               (gen.task/task {:id 4 :parent-id 1 :status :dropped :finish-date (LocalDate/of 2026 9 27)})
               (gen.task/task {:id 5 :parent-id 1 :due-date (LocalDate/of 2026 10 1)})]]
    (is (= [5 4 3 2] (child-ids (entry-of (logic.task/build-tree tasks {:all? true}) 1))))))

(deftest build-tree-keeps-an-open-root-and-hides-its-closed-children
  (let [tasks [(gen.task/task {:id 1})
               (gen.task/task {:id 2 :parent-id 1 :status :done})
               (gen.task/task {:id 3 :parent-id 1 :status :dropped})]
        tree  (logic.task/build-tree tasks {})]
    (is (= [1] (ids-of tree)))
    (is (empty? (:children (entry-of tree 1))))
    (is (not (:context? (entry-of tree 1))))
    (is (= {:done 1 :planned 1} (:progress (entry-of tree 1))))))

(deftest build-tree-shows-only-open-children-but-counts-every-non-dropped-child
  (let [tasks [(gen.task/task {:id 1})
               (gen.task/task {:id 2 :parent-id 1 :status :todo})
               (gen.task/task {:id 3 :parent-id 1 :status :doing})
               (gen.task/task {:id 4 :parent-id 1 :status :done})
               (gen.task/task {:id 5 :parent-id 1 :status :dropped})]
        entry (entry-of (logic.task/build-tree tasks {}) 1)]
    (is (= [2 3] (child-ids entry)))
    (is (= {:done 1 :planned 3} (:progress entry)))))

(deftest build-tree-has-no-progress-when-every-child-is-dropped
  (let [tasks [(gen.task/task {:id 1})
               (gen.task/task {:id 2 :parent-id 1 :status :dropped})]]
    (is (nil? (:progress (entry-of (logic.task/build-tree tasks {}) 1))))
    (is (nil? (:progress (entry-of (logic.task/build-tree tasks {:all? true}) 1))))
    (is (= [2] (child-ids (entry-of (logic.task/build-tree tasks {:all? true}) 1))))))

(deftest build-tree-keeps-a-closed-root-with-an-open-child-as-context
  (let [tasks [(gen.task/task {:id 1 :status :done})
               (gen.task/task {:id 2 :parent-id 1 :status :todo})
               (gen.task/task {:id 3 :parent-id 1 :status :done})]
        tree  (logic.task/build-tree tasks {})
        entry (entry-of tree 1)]
    (is (= [1] (ids-of tree)))
    (is (true? (:context? entry)))
    (is (= [2] (child-ids entry)))
    (is (= {:done 1 :planned 2} (:progress entry)))))

(deftest build-tree-omits-a-closed-root-whose-children-are-all-closed
  (let [tasks [(gen.task/task {:id 1 :status :done})
               (gen.task/task {:id 2 :parent-id 1 :status :done})
               (gen.task/task {:id 3 :parent-id 1 :status :dropped})
               (gen.task/task {:id 4 :status :dropped})
               (gen.task/task {:id 5})]]
    (is (= [5] (ids-of (logic.task/build-tree tasks {}))))))

(deftest build-tree-puts-context-roots-after-open-roots
  (let [tasks [(gen.task/task {:id 1 :status :done})
               (gen.task/task {:id 2 :parent-id 1})
               (gen.task/task {:id 3 :due-date (LocalDate/of 2026 12 1)})
               (gen.task/task {:id 4})]]
    (is (= [3 4 1] (ids-of (logic.task/build-tree tasks {}))))))

(deftest build-tree-with-all-shows-closed-roots-and-children-without-context
  (let [tasks [(gen.task/task {:id 1 :status :done})
               (gen.task/task {:id 2 :parent-id 1 :status :done})
               (gen.task/task {:id 3 :parent-id 1 :status :dropped})
               (gen.task/task {:id 4 :parent-id 1 :status :todo})
               (gen.task/task {:id 5 :status :dropped})
               (gen.task/task {:id 6 :status :todo})
               (gen.task/task {:id 7 :parent-id 6 :status :done})
               (gen.task/task {:id 8 :parent-id 6 :status :todo})
               (gen.task/task {:id 9 :parent-id 6 :status :dropped})]
        tree  (logic.task/build-tree tasks {:all? true})]
    (is (= [6 5 1] (ids-of tree)))
    (is (= [8 9 7] (child-ids (entry-of tree 6))))
    (is (= [4 3 2] (child-ids (entry-of tree 1))))
    (is (not-any? :context? tree))
    (is (= {:done 1 :planned 2} (:progress (entry-of tree 6))))
    (is (= {:done 1 :planned 2} (:progress (entry-of tree 1))))))

(defn- roots-with-children
  "n open roots with ids 1..n, each followed by one child."
  [n]
  (vec (mapcat (fn [i]
                 [(gen.task/task {:id i})
                  (gen.task/task {:id (+ 100 i) :parent-id i})])
               (range 1 (inc n)))))

(deftest page-roots-with-fewer-roots-than-the-limit-has-no-more
  (let [tasks (roots-with-children 2)]
    (is (= {:tasks tasks :more? false} (logic.task/page-roots tasks 3)))))

(deftest page-roots-with-exactly-the-limit-has-no-more
  (let [tasks (roots-with-children 3)]
    (is (= {:tasks tasks :more? false} (logic.task/page-roots tasks 3)))))

(deftest page-roots-drops-the-extra-root-and-its-children
  (let [tasks (roots-with-children 4)]
    (is (= {:tasks (vec (take 6 tasks)) :more? true} (logic.task/page-roots tasks 3)))))

(def ^:private transition-day (LocalDate/of 2026 10 5))

(defn- transition-subject
  "A subtask with a due date and note, so a transition is seen to keep them."
  [status]
  (gen.task/task {:id 7 :parent-id 3 :note "for Q3" :due-date (LocalDate/of 2026 10 1) :status status}))

(defn- illegal [status]
  {:error/code :task/illegal-transition :error/details {:id 7 :status status}})

(defn- applied [status & {:as changes}]
  {:outcome :applied :task (merge (transition-subject status) changes)})

(defn- unchanged [status]
  {:outcome :unchanged :task (transition-subject status)})

(defn- transition-cells
  "Runs command from every status and compares with the expected result."
  [command expected-by-status]
  (doseq [[status expected] expected-by-status]
    (testing (str command " from " status)
      (is (= expected (logic.task/transition (transition-subject status) command transition-day))))))

(deftest transition-start-moves-todo-to-doing-and-rejects-closed-tasks
  (transition-cells :start
                    {:todo    (applied :todo :status :doing)
                     :doing   (unchanged :doing)
                     :done    (illegal :done)
                     :dropped (illegal :dropped)}))

(deftest transition-done-closes-open-tasks-with-today-as-the-finish-date
  (transition-cells :done
                    {:todo    (applied :todo :status :done :finish-date transition-day)
                     :doing   (applied :doing :status :done :finish-date transition-day)
                     :done    (unchanged :done)
                     :dropped (illegal :dropped)}))

(deftest transition-drop-closes-open-tasks-with-today-as-the-finish-date
  (transition-cells :drop
                    {:todo    (applied :todo :status :dropped :finish-date transition-day)
                     :doing   (applied :doing :status :dropped :finish-date transition-day)
                     :done    (illegal :done)
                     :dropped (unchanged :dropped)}))

(deftest transition-reopen-returns-closed-tasks-to-todo-without-a-finish-date
  (transition-cells :reopen
                    {:todo    (unchanged :todo)
                     :doing   (unchanged :doing)
                     :done    (applied :done :status :todo :finish-date nil)
                     :dropped (applied :dropped :status :todo :finish-date nil)}))

(deftest transition-unchanged-keeps-the-original-finish-date
  (let [done (gen.task/task {:id 7 :status :done :finish-date (LocalDate/of 2026 9 20)})]
    (is (= {:outcome :unchanged :task done}
           (logic.task/transition done :done transition-day)))))

(def ^:private day (LocalDate/of 2026 9 20))

(defn- day-task
  "A task whose id is also its title, so a failing comparison reads clearly."
  [id overrides]
  (gen.task/task (merge {:id id :title (str "task " id)} overrides)))

(deftest day-sections-puts-a-task-finished-on-the-day-in-done
  (let [t (day-task 4 {:status :done :finish-date day :created-at (LocalDateTime/of 2026 9 10 9 0 0)})]
    (is (= {:done [t] :dropped [] :open []} (logic.task/day-sections [t] day)))))

(deftest day-sections-puts-a-task-dropped-on-the-day-in-dropped
  (let [t (day-task 4 {:status :dropped :finish-date day :created-at (LocalDateTime/of 2026 9 10 9 0 0)})]
    (is (= {:done [] :dropped [t] :open []} (logic.task/day-sections [t] day)))))

(deftest day-sections-puts-a-task-open-across-the-day-in-open
  (let [todo  (day-task 1 {:status :todo :created-at (LocalDateTime/of 2026 9 10 9 0 0)})
        doing (day-task 2 {:status :doing :created-at (LocalDateTime/of 2026 9 20 23 59 59)})]
    (is (= {:done [] :dropped [] :open [todo doing]} (logic.task/day-sections [todo doing] day)))))

(deftest day-sections-leaves-out-a-task-created-after-the-day
  (let [later    (day-task 1 {:created-at (LocalDateTime/of 2026 9 21 0 0 0)})
        much-later (day-task 2 {:created-at (LocalDateTime/of 2026 9 28 12 0 0)})]
    (is (= {:done [] :dropped [] :open []} (logic.task/day-sections [later much-later] day)))))

(deftest day-sections-leaves-out-a-task-finished-before-the-day
  (let [done    (day-task 1 {:status :done :finish-date (LocalDate/of 2026 9 19)
                             :created-at (LocalDateTime/of 2026 9 10 9 0 0)})
        dropped (day-task 2 {:status :dropped :finish-date (LocalDate/of 2026 9 1)
                             :created-at (LocalDateTime/of 2026 8 30 9 0 0)})]
    (is (= {:done [] :dropped [] :open []} (logic.task/day-sections [done dropped] day)))))

(deftest day-sections-reads-a-task-finished-after-the-day-as-open-on-the-day
  (let [done-later (day-task 1 {:status :done :finish-date (LocalDate/of 2026 9 22)
                                :created-at (LocalDateTime/of 2026 9 10 9 0 0)})]
    (is (= {:done [] :dropped [] :open [done-later]} (logic.task/day-sections [done-later] day)))))

(deftest day-sections-reads-a-task-reopened-after-the-day-as-open
  (let [done     (day-task 1 {:status :done :finish-date day :created-at (LocalDateTime/of 2026 9 10 9 0 0)})
        reopened (assoc done :status :todo :finish-date nil)]
    (is (= {:done [] :dropped [] :open [reopened]} (logic.task/day-sections [reopened] day)))
    (is (= {:done [done] :dropped [] :open []} (logic.task/day-sections [done] day)))))

(deftest day-sections-keeps-the-sections-disjoint
  (let [created (LocalDateTime/of 2026 9 10 9 0 0)
        done-1  (day-task 1 {:status :done :finish-date day :created-at created})
        dropped (day-task 2 {:status :dropped :finish-date day :created-at created})
        doing   (day-task 3 {:status :doing :created-at created})
        done-4  (day-task 4 {:status :done :finish-date day :created-at (LocalDateTime/of 2026 9 20 23 59 59)})]
    (is (= {:done [done-1 done-4] :dropped [dropped] :open [doing]}
           (logic.task/day-sections [done-1 dropped doing done-4] day)))))

(deftest day-sections-sorts-each-section-by-id
  (let [created (LocalDateTime/of 2026 9 10 9 0 0)
        done    #(day-task % {:status :done :finish-date day :created-at created})
        dropped #(day-task % {:status :dropped :finish-date day :created-at created})
        todo    (day-task 7 {:status :todo :created-at created})
        doing   (day-task 5 {:status :doing :created-at created})]
    (is (= {:done    [(done 2) (done 6) (done 9)]
            :dropped [(dropped 3) (dropped 8)]
            :open    [doing todo]}
           (logic.task/day-sections [(done 9) (dropped 3) todo (done 2) (dropped 8) doing (done 6)] day)))))

(deftest day-sections-keeps-a-subtask-in-the-section-of-its-own-status
  (let [created (LocalDateTime/of 2026 9 10 9 0 0)
        parent  (day-task 1 {:status :doing :created-at created})
        child   (day-task 2 {:parent-id 1 :status :done :finish-date day :created-at created})]
    (is (= {:done [child] :dropped [] :open [parent]}
           (logic.task/day-sections [parent child] day)))))

(deftest day-sections-of-no-tasks-is-three-empty-sections
  (is (= {:done [] :dropped [] :open []} (logic.task/day-sections [] day))))
