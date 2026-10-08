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

(def ^:private list-order
  "The same order as logic.task/build-tree, so LIMIT and OFFSET page through it:
  open before closed; open by due date ascending with undated last, then id;
  closed by finish date descending, then id descending."
  "ORDER BY status IN ('done', 'dropped'),
         CASE WHEN status IN ('todo', 'doing') THEN due_date IS NULL END,
         CASE WHEN status IN ('todo', 'doing') THEN due_date END,
         CASE WHEN status IN ('todo', 'doing') THEN id END,
         finish_date DESC,
         id DESC")

(defn list-open
  [limit offset db-path]
  (mapv adapters.task/row->task
        (sqlite/query db-path [(str "SELECT * FROM tasks WHERE status IN ('todo', 'doing') "
                                    list-order " LIMIT ? OFFSET ?")
                               limit offset])))

(defn list-all
  [limit offset db-path]
  (mapv adapters.task/row->task
        (sqlite/query db-path [(str "SELECT * FROM tasks " list-order " LIMIT ? OFFSET ?")
                               limit offset])))
