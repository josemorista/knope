(ns knope.diplomat.sqlite.task-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [knope.adapters.task :as adapters.task]
            [knope.diplomat.sqlite.task :as sqlite.task]
            [knope.gen.task :as gen.task]
            [knope.helpers :as helpers]
            [knope.logic.task :as logic.task]
            [pod.babashka.go-sqlite3 :as sqlite])
  (:import (java.time LocalDate LocalDateTime)))

(use-fixtures :each helpers/with-components)

(defn- db [] (:sqlite helpers/*components*))

(deftest insert-then-find-returns-an-equal-task
  (let [task (gen.task/task {:id nil :note "for Q3" :due-date (LocalDate/of 2026 10 1)})
        id   (sqlite.task/insert! task (db))]
    (is (= 1 id))
    (is (= (assoc task :id id) (sqlite.task/find-by-id id (db))))))

(deftest insert-returns-a-fresh-id-each-time
  (is (= [1 2 3]
         (mapv (fn [title] (sqlite.task/insert! (gen.task/task {:id nil :title title}) (db)))
               ["a" "b" "c"]))))

(deftest insert-round-trips-every-status-and-a-parent
  (let [parent-id (sqlite.task/insert! (gen.task/task {:id nil :title "Parent"}) (db))]
    (doseq [status [:todo :doing :done :dropped]]
      (testing status
        (let [task (gen.task/task {:id nil :parent-id parent-id :status status
                                   :created-at (LocalDateTime/of 2026 9 20 8 30 45)})
              id   (sqlite.task/insert! task (db))]
          (is (= (assoc task :id id) (sqlite.task/find-by-id id (db)))))))))

(deftest insert-stores-created-at-with-zero-seconds
  (let [id (sqlite.task/insert! (gen.task/task {:id nil :created-at (LocalDateTime/of 2026 9 28 12 0 0)})
                                (db))]
    (is (= [{:created_at "2026-09-28T12:00:00"}]
           (sqlite/query (db) ["SELECT created_at FROM tasks WHERE id = ?" id])))))

(deftest find-by-id-returns-nil-for-an-unknown-id
  (is (nil? (sqlite.task/find-by-id 99 (db)))))

(deftest insert-populates-the-search-index
  (sqlite.task/insert! (gen.task/task {:id nil :title "Write quarterly report"}) (db))
  (is (= [{:rowid 1}]
         (sqlite/query (db) ["SELECT rowid FROM tasks_fts WHERE tasks_fts MATCH ?" "quarterly*"]))))

(defn- insert-all! [tasks]
  (mapv #(assoc % :id (sqlite.task/insert! % (db))) tasks))

(def ^:private mixed-tasks
  "Open and closed tasks whose sorted order differs from insertion order."
  [(gen.task/task {:id nil :title "a" :status :done :finish-date (LocalDate/of 2026 9 27)})
   (gen.task/task {:id nil :title "b" :due-date nil})
   (gen.task/task {:id nil :title "c" :status :dropped :finish-date (LocalDate/of 2026 9 28)})
   (gen.task/task {:id nil :title "d" :status :doing :due-date (LocalDate/of 2026 10 5)})
   (gen.task/task {:id nil :title "e" :status :done :finish-date (LocalDate/of 2026 9 27)})
   (gen.task/task {:id nil :title "f" :due-date (LocalDate/of 2026 9 29)})
   (gen.task/task {:id nil :title "g" :due-date nil})
   (gen.task/task {:id nil :title "h" :status :done :finish-date (LocalDate/of 2026 9 20)})
   (gen.task/task {:id nil :title "i" :due-date (LocalDate/of 2026 9 29)})])

(defn- sorted-by-logic [tasks all?]
  (mapv :task (logic.task/build-tree tasks {:all? all?})))

(deftest list-all-returns-every-task-in-build-tree-order
  (let [tasks (insert-all! mixed-tasks)]
    (is (= (sorted-by-logic tasks true)
           (sqlite.task/list-all 100 0 (db))))))

(deftest list-open-returns-only-open-tasks-in-build-tree-order
  (let [tasks (insert-all! mixed-tasks)]
    (is (= (sorted-by-logic tasks false)
           (sqlite.task/list-open 100 0 (db))))
    (is (= #{:todo :doing}
           (set (map :status (sqlite.task/list-open 100 0 (db))))))))

(deftest list-all-pages-with-limit-and-offset
  (let [sorted (sorted-by-logic (insert-all! mixed-tasks) true)]
    (is (= (subvec sorted 0 4) (sqlite.task/list-all 4 0 (db))))
    (is (= (subvec sorted 4 8) (sqlite.task/list-all 4 4 (db))))
    (is (= (subvec sorted 8 9) (sqlite.task/list-all 4 8 (db))))
    (is (= [] (sqlite.task/list-all 4 12 (db))))))

(deftest list-open-pages-with-limit-and-offset
  (let [sorted (sorted-by-logic (insert-all! mixed-tasks) false)]
    (is (= (subvec sorted 0 3) (sqlite.task/list-open 3 0 (db))))
    (is (= (subvec sorted 3 5) (sqlite.task/list-open 3 3 (db))))
    (is (= [] (sqlite.task/list-open 3 6 (db))))))

(deftest list-on-an-empty-database-is-empty
  (is (= [] (sqlite.task/list-all 50 0 (db))))
  (is (= [] (sqlite.task/list-open 50 0 (db)))))

(defn- insert-families!
  "Seven roots and seven children, ids fixed by insertion order.
  Open roots in list order: 1 3 2 7. Closed roots in --all order after them: 6 4 5.
  Root 4 is closed with an open child, 5 is closed with only a closed child, 6 has no children."
  []
  (let [ins! (fn [overrides] (sqlite.task/insert! (gen.task/task (assoc overrides :id nil)) (db)))
        d    #(LocalDate/of 2026 9 %)]
    (ins! {:title "R1" :due-date (LocalDate/of 2026 9 29)})
    (ins! {:title "R2"})
    (ins! {:title "R3" :due-date (LocalDate/of 2026 10 1)})
    (ins! {:title "R4" :status :done :finish-date (d 27)})
    (ins! {:title "R5" :status :done :finish-date (d 26)})
    (ins! {:title "R6" :status :dropped :finish-date (d 28)})
    (ins! {:title "R7"})
    (ins! {:title "c1" :parent-id 1 :due-date (d 30)})
    (ins! {:title "c2" :parent-id 1 :status :done})
    (ins! {:title "c3" :parent-id 3 :status :dropped})
    (ins! {:title "c4" :parent-id 4})
    (ins! {:title "c5" :parent-id 5 :status :done})
    (ins! {:title "c6" :parent-id 7 :status :doing})
    (ins! {:title "c7" :parent-id 7})))

(defn- root-ids [tasks] (mapv :id (remove :parent-id tasks)))

(defn- family-ids [tasks] (set (map :id tasks)))

(deftest list-open-pages-by-root-and-returns-every-child-of-the-page-roots
  (insert-families!)
  (testing "open roots, then closed roots that have an open child"
    (is (= [1 3] (root-ids (sqlite.task/list-open 2 0 (db)))))
    (is (= [2 7] (root-ids (sqlite.task/list-open 2 2 (db)))))
    (is (= [4] (root-ids (sqlite.task/list-open 2 4 (db))))))
  (testing "a page never splits a parent from its children, closed children included"
    (is (= #{1 3 8 9 10} (family-ids (sqlite.task/list-open 2 0 (db)))))
    (is (= #{2 7 13 14} (family-ids (sqlite.task/list-open 2 2 (db)))))
    (is (= #{4 11} (family-ids (sqlite.task/list-open 2 4 (db))))))
  (testing "children do not count against the limit"
    (is (= #{1 3 2 8 9 10} (family-ids (sqlite.task/list-open 3 0 (db))))))
  (testing "past the last root"
    (is (= [] (sqlite.task/list-open 2 5 (db))))))

(deftest list-all-pages-by-root-and-returns-every-child-of-the-page-roots
  (insert-families!)
  (is (= [1 3 2] (root-ids (sqlite.task/list-all 3 0 (db)))))
  (is (= [7 6 4] (root-ids (sqlite.task/list-all 3 3 (db)))))
  (is (= [5] (root-ids (sqlite.task/list-all 3 6 (db)))))
  (is (= #{1 3 2 8 9 10} (family-ids (sqlite.task/list-all 3 0 (db)))))
  (is (= #{7 13 14 6 4 11} (family-ids (sqlite.task/list-all 3 3 (db)))))
  (is (= #{5 12} (family-ids (sqlite.task/list-all 3 6 (db)))))
  (is (= [] (sqlite.task/list-all 3 7 (db)))))

(deftest list-queries-order-roots-like-build-tree
  (insert-families!)
  (let [all-tasks (sqlite.task/list-all 100 0 (db))]
    (is (= 14 (count all-tasks)))
    (is (= (mapv #(:id (:task %)) (logic.task/build-tree all-tasks {:all? true}))
           (root-ids all-tasks)))
    (is (= (mapv #(:id (:task %)) (logic.task/build-tree all-tasks {}))
           (root-ids (sqlite.task/list-open 100 0 (db)))))))

(deftest update-then-find-returns-the-updated-task
  (let [id      (sqlite.task/insert! (gen.task/task {:id nil :title "Old" :note "old note"
                                                     :due-date (LocalDate/of 2026 10 1)})
                                     (db))
        updated (gen.task/task {:id id :title "New" :note "new note" :status :done
                                :due-date (LocalDate/of 2026 10 9)
                                :finish-date (LocalDate/of 2026 10 5)})]
    (sqlite.task/update! updated (db))
    (is (= updated (sqlite.task/find-by-id id (db))))))

(deftest update-can-null-the-optional-columns
  (let [id      (sqlite.task/insert! (gen.task/task {:id nil :note "n" :status :done
                                                     :due-date (LocalDate/of 2026 10 1)})
                                     (db))
        updated (gen.task/task {:id id :note nil :status :todo :due-date nil :finish-date nil})]
    (sqlite.task/update! updated (db))
    (is (= updated (sqlite.task/find-by-id id (db))))))

(deftest update-leaves-other-tasks-alone
  (let [[first-task other] (insert-all! [(gen.task/task {:id nil :title "first"})
                                         (gen.task/task {:id nil :title "other"})])]
    (sqlite.task/update! (assoc first-task :status :doing) (db))
    (is (= other (sqlite.task/find-by-id (:id other) (db))))
    (is (= (assoc first-task :status :doing) (sqlite.task/find-by-id (:id first-task) (db))))))

(deftest count-children-counts-only-the-direct-subtasks-of-a-task
  (insert-all! [(gen.task/task {:id nil :title "Parent"})
                (gen.task/task {:id nil :title "Other parent"})
                (gen.task/task {:id nil :title "Step one" :parent-id 1})
                (gen.task/task {:id nil :title "Step two" :parent-id 1 :status :done})
                (gen.task/task {:id nil :title "Other step" :parent-id 2})])
  (is (= 2 (sqlite.task/count-children 1 (db))))
  (is (= 1 (sqlite.task/count-children 2 (db))))
  (is (= 0 (sqlite.task/count-children 3 (db))))
  (is (= 0 (sqlite.task/count-children 99 (db)))))

(deftest delete-removes-a-parent-and-both-its-children-in-one-call
  (let [[_ _ _ _ _ other-parent other-step unrelated]
        (insert-all! [(gen.task/task {:id nil :title "Quarterly parent"})
                      (gen.task/task {:id nil :title "Quarterly first step" :parent-id 1})
                      (gen.task/task {:id nil :title "Quarterly second step" :parent-id 1
                                      :note "quarterly note" :status :done})
                      (gen.task/task {:id nil :title "Quarterly sibling parent"})
                      (gen.task/task {:id nil :title "Quarterly sibling step" :parent-id 4})
                      (gen.task/task {:id nil :title "Annual parent"})
                      (gen.task/task {:id nil :title "Annual step" :parent-id 6})
                      (gen.task/task {:id nil :title "Unrelated"})])]
    (sqlite.task/delete! 1 (db))
    (is (nil? (sqlite.task/find-by-id 1 (db))))
    (is (nil? (sqlite.task/find-by-id 2 (db))))
    (is (nil? (sqlite.task/find-by-id 3 (db))))
    (is (= [4 5 6 7 8] (mapv :id (sqlite/query (db) ["SELECT id FROM tasks ORDER BY id"]))))
    (is (= other-parent (sqlite.task/find-by-id 6 (db))))
    (is (= other-step (sqlite.task/find-by-id 7 (db))))
    (is (= unrelated (sqlite.task/find-by-id 8 (db))))))

(deftest delete-removes-the-family-from-the-search-index
  (insert-all! [(gen.task/task {:id nil :title "Quarterly parent"})
                (gen.task/task {:id nil :title "Quarterly first step" :parent-id 1})
                (gen.task/task {:id nil :title "Second step" :parent-id 1 :note "quarterly note"})
                (gen.task/task {:id nil :title "Quarterly sibling"})])
  (is (= [{:rowid 1} {:rowid 2} {:rowid 3} {:rowid 4}]
         (sqlite/query (db) ["SELECT rowid FROM tasks_fts WHERE tasks_fts MATCH ? ORDER BY rowid" "quarterly*"])))
  (sqlite.task/delete! 1 (db))
  (is (= [{:rowid 4}]
         (sqlite/query (db) ["SELECT rowid FROM tasks_fts WHERE tasks_fts MATCH ? ORDER BY rowid" "quarterly*"]))))

(deftest delete-of-a-subtask-leaves-its-parent-and-sibling
  (let [[parent _ sibling] (insert-all! [(gen.task/task {:id nil :title "Parent"})
                                         (gen.task/task {:id nil :title "Step one" :parent-id 1})
                                         (gen.task/task {:id nil :title "Step two" :parent-id 1})])]
    (sqlite.task/delete! 2 (db))
    (is (nil? (sqlite.task/find-by-id 2 (db))))
    (is (= parent (sqlite.task/find-by-id 1 (db))))
    (is (= sibling (sqlite.task/find-by-id 3 (db))))))

(def ^:private day (LocalDate/of 2026 9 20))

(defn- day-ids
  "Ids of the rows the day query returns for day, in id order."
  []
  (->> (sqlite.task/day-rows day (.plusDays day 1) (db))
       (map :id)
       sort
       vec))

(deftest day-rows-returns-a-task-created-in-the-last-second-of-the-day
  (insert-all! [(gen.task/task {:id nil :title "last second"
                                :created-at (LocalDateTime/of 2026 9 20 23 59 59)})])
  (is (= [1] (day-ids))))

(deftest day-rows-leaves-out-a-task-created-at-midnight-of-the-next-day
  (insert-all! [(gen.task/task {:id nil :title "last second"
                                :created-at (LocalDateTime/of 2026 9 20 23 59 59)})
                (gen.task/task {:id nil :title "first second of the next day"
                                :created-at (LocalDateTime/of 2026 9 21 0 0 0)})])
  (is (= [1] (day-ids))))

(deftest day-rows-returns-an-open-task-created-on-any-earlier-day
  (insert-all! [(gen.task/task {:id nil :created-at (LocalDateTime/of 2026 9 20 0 0 0)})
                (gen.task/task {:id nil :status :doing :created-at (LocalDateTime/of 2026 1 5 8 30 0)})])
  (is (= [1 2] (day-ids))))

(deftest day-rows-returns-a-task-finished-on-the-day-whenever-it-was-created
  (insert-all! [(gen.task/task {:id nil :status :done :finish-date day
                                :created-at (LocalDateTime/of 2026 9 10 9 0 0)})
                (gen.task/task {:id nil :status :dropped :finish-date day
                                :created-at (LocalDateTime/of 2026 9 20 23 59 59)})
                (gen.task/task {:id nil :status :done :finish-date day
                                :created-at (LocalDateTime/of 2026 9 21 0 0 0)})])
  (is (= [1 2 3] (day-ids))))

(deftest day-rows-leaves-out-a-task-finished-before-the-day
  (insert-all! [(gen.task/task {:id nil :title "still open"
                                :created-at (LocalDateTime/of 2026 9 10 9 0 0)})
                (gen.task/task {:id nil :status :done :finish-date (LocalDate/of 2026 9 19)
                                :created-at (LocalDateTime/of 2026 9 10 9 0 0)})
                (gen.task/task {:id nil :status :dropped :finish-date (LocalDate/of 2026 9 1)
                                :created-at (LocalDateTime/of 2026 8 30 9 0 0)})])
  (is (= [1] (day-ids))))

(deftest day-rows-returns-a-task-created-before-the-day-and-finished-after-it
  (insert-all! [(gen.task/task {:id nil :status :done :finish-date (LocalDate/of 2026 9 21)
                                :created-at (LocalDateTime/of 2026 9 10 9 0 0)})
                (gen.task/task {:id nil :status :done :finish-date (LocalDate/of 2026 9 21)
                                :created-at (LocalDateTime/of 2026 9 21 8 0 0)})])
  (is (= [1] (day-ids))))

(deftest day-rows-returns-whole-tasks-and-subtasks
  (let [[parent child] (insert-all! [(gen.task/task {:id nil :title "Parent" :note "context"
                                                     :due-date (LocalDate/of 2026 10 1)
                                                     :created-at (LocalDateTime/of 2026 9 10 9 0 0)})
                                     (gen.task/task {:id nil :title "Child" :parent-id 1
                                                     :status :done :finish-date day
                                                     :created-at (LocalDateTime/of 2026 9 11 9 0 0)})])]
    (is (= [parent child]
           (sort-by :id (sqlite.task/day-rows day (.plusDays day 1) (db)))))))

;; The plan is read for the statement day-rows runs, taken from its var.
(deftest day-query-plan-uses-the-finish-date-and-creation-time-indexes
  (insert-all! (for [n (range 40)]
                 (gen.task/task {:id nil :title (str "task " n)
                                 :created-at (LocalDateTime/of 2026 9 (inc (mod n 28)) 10 0 0)})))
  (is (seq (sqlite.task/day-rows day (.plusDays day 1) (db))))
  (let [plan (->> (sqlite/query (db) [(str "EXPLAIN QUERY PLAN " @#'sqlite.task/day-sql)
                                      "2026-09-20" "2026-09-21" "2026-09-20"])
                  (map :detail)
                  (str/join "\n"))]
    (is (str/includes? plan "tasks_finish_date_idx") plan)
    (is (str/includes? plan "tasks_created_at_idx") plan)))

(defn- search-ids
  "Ids of the tasks search returns for the typed text, in result order."
  [text]
  (mapv :id (sqlite.task/search (adapters.task/search-match text) (db))))

(deftest search-finds-a-task-by-the-prefix-of-every-word
  (insert-all! [(gen.task/task {:id nil :title "Authentication service"})
                (gen.task/task {:id nil :title "Authentication only"})
                (gen.task/task {:id nil :title "Billing service"})])
  (is (= [1] (search-ids "auth serv")))
  (is (= [1 2] (sort (search-ids "authen"))))
  (is (= [] (search-ids "uthentication"))))

(deftest search-returns-whole-tasks
  (let [[task] (insert-all! [(gen.task/task {:id nil :title "Authentication service" :note "for Q3"
                                             :due-date (LocalDate/of 2026 10 1)})
                             (gen.task/task {:id nil :title "Billing"})])]
    (is (= [task] (sqlite.task/search (adapters.task/search-match "auth") (db))))))

(deftest search-matches-the-note-as-well-as-the-title
  (insert-all! [(gen.task/task {:id nil :title "Login flow" :note "Autenticação do serviço"})
                (gen.task/task {:id nil :title "Billing"})])
  (is (= [1] (search-ids "serviço"))))

(deftest search-ignores-case
  (insert-all! [(gen.task/task {:id nil :title "Authentication service"})])
  (is (= [1] (search-ids "AUTH")))
  (is (= [1] (search-ids "Serv"))))

(deftest search-ignores-accents-in-the-text-and-in-the-query
  (insert-all! [(gen.task/task {:id nil :title "Việt Nam trip"})
                (gen.task/task {:id nil :title "Login flow" :note "Autenticação do serviço"})])
  (is (= [1] (search-ids "viet")))
  (is (= [1] (search-ids "Việt")))
  (is (= [2] (search-ids "autenticacao")))
  (is (= [2] (search-ids "servico"))))

(deftest search-covers-every-status-and-subtasks
  (insert-all! [(gen.task/task {:id nil :title "Auth todo"})
                (gen.task/task {:id nil :title "Auth doing" :status :doing})
                (gen.task/task {:id nil :title "Auth done" :status :done})
                (gen.task/task {:id nil :title "Auth dropped" :status :dropped})
                (gen.task/task {:id nil :title "Auth step" :parent-id 1})])
  (is (= [1 2 3 4 5] (sort (search-ids "auth")))))

(deftest search-ranks-the-more-relevant-task-first
  (insert-all! [(gen.task/task {:id nil :title "Quarterly planning meeting notes and agenda for everyone"})
                (gen.task/task {:id nil :title "Quarterly"})])
  (is (= [2 1] (search-ids "quarterly"))))

(deftest search-with-no-match-returns-nothing
  (insert-all! [(gen.task/task {:id nil :title "Authentication service"})])
  (is (= [1] (search-ids "auth")))
  (is (= [] (search-ids "billing")))
  (is (= [] (search-ids "auth billing"))))

(deftest search-returns-nothing-until-a-matching-task-exists
  (is (= [] (search-ids "auth")))
  (insert-all! [(gen.task/task {:id nil :title "Authentication service"})])
  (is (= [1] (search-ids "auth"))))

(deftest search-runs-an-expression-built-from-a-word-with-a-dot-without-an-fts-error
  (insert-all! [(gen.task/task {:id nil :title "auth.svc rollout"})
                (gen.task/task {:id nil :title "Billing"})])
  (is (= [1] (search-ids "auth.svc"))))

(deftest search-runs-an-expression-built-from-a-word-with-a-double-quote-without-an-fts-error
  (insert-all! [(gen.task/task {:id nil :title "Say \"hi\" to the team"})
                (gen.task/task {:id nil :title "Billing"})])
  (is (= [1] (search-ids "\"hi\"")))
  (is (= [1] (search-ids "say\"hi"))))

(deftest search-ignores-punctuation-only-words-beside-real-ones
  (insert-all! [(gen.task/task {:id nil :title "Authentication service"})])
  (is (= [1] (search-ids "- auth . \" serv"))))

(deftest search-finds-the-new-title-after-an-update-and-not-the-old-one
  (let [[task] (insert-all! [(gen.task/task {:id nil :title "Authentication service" :note "legacy"})])]
    (sqlite.task/update! (assoc task :title "Billing report" :note "invoices") (db))
    (is (= [] (search-ids "authentication")))
    (is (= [] (search-ids "legacy")))
    (is (= [1] (search-ids "billing")))
    (is (= [1] (search-ids "invoices")))))

(deftest search-finds-nothing-after-the-task-is-deleted
  (insert-all! [(gen.task/task {:id nil :title "Authentication service"})
                (gen.task/task {:id nil :title "Authentication step" :parent-id 1})
                (gen.task/task {:id nil :title "Authentication docs"})])
  (sqlite.task/delete! 1 (db))
  (is (= [3] (search-ids "auth"))))

(deftest search-still-finds-a-task-after-a-status-update
  (let [[task] (insert-all! [(gen.task/task {:id nil :title "Authentication service"})])
        done   (assoc task :status :done :finish-date (LocalDate/of 2026 9 28))]
    (sqlite.task/update! done (db))
    (is (= [done] (sqlite.task/search (adapters.task/search-match "auth serv") (db))))))

(deftest find-with-children-returns-a-parent-with-its-subtasks
  (let [[parent step-one step-two] (insert-all! [(gen.task/task {:id nil :title "Parent" :note "n"})
                                                 (gen.task/task {:id nil :title "Step one" :parent-id 1})
                                                 (gen.task/task {:id nil :title "Step two" :parent-id 1 :status :done})
                                                 (gen.task/task {:id nil :title "Other parent"})
                                                 (gen.task/task {:id nil :title "Other step" :parent-id 4})])]
    (is (= [parent step-one step-two]
           (sort-by :id (sqlite.task/find-with-children 1 (db)))))))

(deftest find-with-children-returns-only-a-subtask-itself
  (let [[_ step] (insert-all! [(gen.task/task {:id nil :title "Parent"})
                               (gen.task/task {:id nil :title "Step" :parent-id 1})
                               (gen.task/task {:id nil :title "Sibling step" :parent-id 1})])]
    (is (= [step] (vec (sqlite.task/find-with-children 2 (db)))))))

(deftest find-with-children-returns-a-task-without-subtasks-alone
  (let [[_ other] (insert-all! [(gen.task/task {:id nil :title "Parent"})
                                (gen.task/task {:id nil :title "Other"})])]
    (is (= [other] (vec (sqlite.task/find-with-children 2 (db)))))))

(deftest find-with-children-returns-nothing-for-an-unknown-id
  (insert-all! [(gen.task/task {:id nil :title "Parent"})
                (gen.task/task {:id nil :title "Step" :parent-id 1})])
  (is (empty? (sqlite.task/find-with-children 99 (db)))))
