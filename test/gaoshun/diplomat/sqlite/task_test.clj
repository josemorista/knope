(ns gaoshun.diplomat.sqlite.task-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [gaoshun.diplomat.sqlite.task :as sqlite.task]
            [gaoshun.gen.task :as gen.task]
            [gaoshun.helpers :as helpers]
            [gaoshun.logic.task :as logic.task]
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
