(ns gaoshun.diplomat.sqlite.task-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [gaoshun.diplomat.sqlite.task :as sqlite.task]
            [gaoshun.gen.task :as gen.task]
            [gaoshun.helpers :as helpers]
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

(deftest list-all-returns-every-task-regardless-of-status
  (let [tasks (mapv (fn [status title] (gen.task/task {:id nil :title title :status status}))
                    [:todo :doing :done :dropped]
                    ["a" "b" "c" "d"])
        ids   (mapv #(sqlite.task/insert! % (db)) tasks)]
    (is (= (mapv #(assoc %1 :id %2) tasks ids)
           (vec (sort-by :id (sqlite.task/list-all (db))))))))

(deftest list-all-on-an-empty-database-is-empty
  (is (empty? (sqlite.task/list-all (db)))))
