(ns gaoshun.diplomat.sqlite.task
  (:require [gaoshun.adapters.task :as adapters.task]
            [pod.babashka.go-sqlite3 :as sqlite]))

(defn insert!
  [task db-path]
  (:last-inserted-id
   (sqlite/execute! db-path
                    (into ["INSERT INTO tasks (parent_id, title, note, status, created_at, due_date, finish_date)
VALUES (?, ?, ?, ?, ?, ?, ?)"]
                          (adapters.task/task->params task)))))

(defn find-by-id
  [id db-path]
  (some-> (sqlite/query db-path ["SELECT * FROM tasks WHERE id = ?" id])
          first
          adapters.task/row->task))

(defn list-all
  [db-path]
  (mapv adapters.task/row->task (sqlite/query db-path ["SELECT * FROM tasks"])))
