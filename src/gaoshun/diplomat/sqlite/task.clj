(ns gaoshun.diplomat.sqlite.task
  (:require [clojure.string :as str]
            [gaoshun.adapters.task :as adapters.task]
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

(defn- page-with-children
  "The roots selected by root-where, paged, followed by every child of those
  roots. A page counts root families, so a parent is never split from its
  children."
  [root-where limit offset db-path]
  (let [roots (mapv adapters.task/row->task
                    (sqlite/query db-path [(str "SELECT * FROM tasks WHERE parent_id IS NULL" root-where
                                                " " list-order " LIMIT ? OFFSET ?")
                                           limit offset]))]
    (if (empty? roots)
      roots
      (into roots
            (map adapters.task/row->task)
            (sqlite/query db-path
                          (into [(str "SELECT * FROM tasks WHERE parent_id IN ("
                                      (str/join ", " (repeat (count roots) "?")) ")")]
                                (map :id roots)))))))

(defn list-open
  [limit offset db-path]
  (page-with-children " AND (status IN ('todo', 'doing')
       OR id IN (SELECT parent_id FROM tasks WHERE status IN ('todo', 'doing')))"
                      limit offset db-path))

(defn list-all
  [limit offset db-path]
  (page-with-children "" limit offset db-path))

(defn update!
  [{:keys [id] :as task} db-path]
  (sqlite/execute! db-path
                   (into ["UPDATE tasks SET parent_id = ?, title = ?, note = ?, status = ?,
created_at = ?, due_date = ?, finish_date = ? WHERE id = ?"]
                         (conj (adapters.task/task->params task) id)))
  nil)

(defn count-children
  [id db-path]
  (:n (first (sqlite/query db-path ["SELECT COUNT(*) AS n FROM tasks WHERE parent_id = ?" id]))))

(defn delete!
  "Deletes task id and every subtask of it in one statement."
  [id db-path]
  (sqlite/execute! db-path ["DELETE FROM tasks WHERE id = ? OR parent_id = ?" id id])
  nil)
