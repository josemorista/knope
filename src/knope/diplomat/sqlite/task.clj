(ns knope.diplomat.sqlite.task
  (:require [clojure.string :as str]
            [knope.adapters.task :as adapters.task]
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

(defn find-by-ids
  "The tasks with the given ids, in no particular order."
  [ids db-path]
  (mapv adapters.task/row->task
        (sqlite/query db-path
                      (into [(str "SELECT * FROM tasks WHERE id IN ("
                                  (str/join ", " (repeat (count ids) "?")) ")")]
                            ids))))

(defn find-with-children
  "Task id and every subtask of it, in no particular order."
  [id db-path]
  (mapv adapters.task/row->task
        (sqlite/query db-path ["SELECT * FROM tasks WHERE id = ? OR parent_id = ?" id id])))

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

(def ^:private day-sql
  "SELECT * FROM tasks WHERE finish_date = ? OR (created_at < ? AND (finish_date IS NULL OR finish_date > ?))")

(defn day-rows
  "Every task finished on d, or created before next-day and not finished on or
  before d. created_at is compared as a raw string so its index serves the
  query."
  [d next-day db-path]
  (mapv adapters.task/row->task
        (sqlite/query db-path [day-sql (str d) (str next-day) (str d)])))

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

(defn search
  "Every task the match expression finds, most relevant first."
  [match db-path]
  (mapv adapters.task/row->task
        (sqlite/query db-path
                      ["SELECT t.* FROM tasks_fts f JOIN tasks t ON t.id = f.rowid
WHERE tasks_fts MATCH ? ORDER BY bm25(tasks_fts)"
                       match])))

(defn any-task?
  [db-path]
  (boolean (seq (sqlite/query db-path ["SELECT 1 FROM tasks LIMIT 1"]))))
