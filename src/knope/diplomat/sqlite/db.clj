(ns knope.diplomat.sqlite.db
  (:require [babashka.fs :as fs]
            [pod.babashka.go-sqlite3 :as sqlite]))

(def ^:private schema-statements
  ["CREATE TABLE IF NOT EXISTS tasks (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  parent_id   INTEGER REFERENCES tasks(id),
  title       TEXT NOT NULL,
  note        TEXT,
  status      TEXT NOT NULL DEFAULT 'todo',
  created_at  TEXT NOT NULL,
  due_date    TEXT,
  finish_date TEXT
)"
   "CREATE INDEX IF NOT EXISTS tasks_parent_idx     ON tasks(parent_id)"
   "CREATE INDEX IF NOT EXISTS tasks_status_idx     ON tasks(status)"
   "CREATE INDEX IF NOT EXISTS tasks_created_at_idx ON tasks(created_at)"
   "CREATE INDEX IF NOT EXISTS tasks_finish_date_idx ON tasks(finish_date)"
   "CREATE VIRTUAL TABLE IF NOT EXISTS tasks_fts
  USING fts5(title, note, content='tasks', content_rowid='id',
             tokenize=\"unicode61 remove_diacritics 2\")"
   "CREATE TRIGGER IF NOT EXISTS tasks_fts_ai AFTER INSERT ON tasks BEGIN
  INSERT INTO tasks_fts(rowid, title, note) VALUES (new.id, new.title, new.note);
END"
   "CREATE TRIGGER IF NOT EXISTS tasks_fts_ad AFTER DELETE ON tasks BEGIN
  INSERT INTO tasks_fts(tasks_fts, rowid, title, note)
    VALUES ('delete', old.id, old.title, old.note);
END"
   "CREATE TRIGGER IF NOT EXISTS tasks_fts_au AFTER UPDATE OF title, note ON tasks
  WHEN old.title IS NOT new.title OR old.note IS NOT new.note BEGIN
  INSERT INTO tasks_fts(tasks_fts, rowid, title, note)
    VALUES ('delete', old.id, old.title, old.note);
  INSERT INTO tasks_fts(rowid, title, note) VALUES (new.id, new.title, new.note);
END"])

(defn- user-version [db-path]
  (:user_version (first (sqlite/query db-path ["PRAGMA user_version"]))))

(defn ensure-schema!
  [db-path]
  (fs/create-dirs (fs/parent (fs/absolutize db-path)))
  (when (zero? (user-version db-path))
    (doseq [statement schema-statements]
      (sqlite/execute! db-path [statement]))
    (sqlite/execute! db-path ["PRAGMA user_version = 1"])))
