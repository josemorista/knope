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
