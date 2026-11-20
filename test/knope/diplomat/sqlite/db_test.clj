(ns knope.diplomat.sqlite.db-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [knope.components :as components]
            [knope.diplomat.cli :as cli]
            [knope.diplomat.sqlite.db :as db]
            [knope.diplomat.sqlite.task :as sqlite.task]
            [knope.gen.task :as gen.task]
            [knope.helpers :as helpers]
            [pod.babashka.go-sqlite3 :as sqlite]))

(use-fixtures :each helpers/with-components)

(defn- user-version [path]
  (:user_version (first (sqlite/query path ["PRAGMA user_version"]))))

(defn- names-of [path type]
  (->> (sqlite/query path ["SELECT name FROM sqlite_master WHERE type = ? AND name NOT LIKE 'sqlite_%' ORDER BY name" type])
       (map :name)
       set))

(defn- snapshot [path]
  (sqlite/query path ["SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY name"]))

(deftest components-carry-the-database-path-and-clock
  (is (= helpers/*db-path* (:sqlite helpers/*components*)))
  (is (= helpers/fixed-now ((:clock helpers/*components*)))))

(deftest components-fall-back-to-home-directory
  (let [home (fs/create-temp-dir {:prefix "knope-home-"})]
    (try
      (let [built     (components/components {"HOME" (str home)})
            expected (str (fs/path home ".knope" "tasks.db"))]
        (is (= expected (:sqlite built)))
        (is (fs/exists? expected))
        (is (= 1 (user-version expected)))
        (is (fn? (:clock built))))
      (finally
        (fs/delete-tree home)))))

(deftest components-treat-an-empty-knope-db-as-unset
  (let [home (fs/create-temp-dir {:prefix "knope-home-"})]
    (try
      (is (= (str (fs/path home ".knope" "tasks.db"))
             (:sqlite (components/components {"KNOPE_DB" "" "HOME" (str home)}))))
      (finally
        (fs/delete-tree home)))))

(deftest knope-db-moves-the-data-file-and-leaves-home-untouched
  (let [home  (fs/create-temp-dir {:prefix "knope-home-"})
        other (str (fs/path (fs/create-temp-dir {:prefix "knope-test-"}) "other.db"))]
    (try
      (let [built  (components/components {"HOME" (str home) "KNOPE_DB" other})
            added  (cli/run built {:color? false} ["add" "Moved task"])
            listed (cli/run built {:color? false} ["list"])]
        (is (= other (:sqlite built)))
        (is (= 0 (:exit added)))
        (is (= 0 (:exit listed)))
        (is (str/includes? (:out listed) "Moved task")))
      (is (fs/exists? other))
      (is (not (fs/exists? (fs/path home ".knope"))))
      (finally
        (fs/delete-tree home)
        (fs/delete-tree (fs/parent other))))))

(deftest components-resolve-a-relative-knope-db-to-an-absolute-path
  (let [dir      (fs/create-temp-dir {:prefix "knope-rel-"})
        expected (str (fs/path dir "tasks.db"))]
    (try
      (is (= expected
             (:sqlite (components/components
                       {"KNOPE_DB" (str (fs/relativize (fs/cwd) expected))}))))
      (finally
        (fs/delete-tree dir)))))

(deftest fresh-database-ends-at-user-version-1
  (is (= 1 (user-version helpers/*db-path*))))

(deftest fresh-database-has-the-tasks-table
  (is (= ["id" "parent_id" "title" "note" "status" "created_at" "due_date" "finish_date"]
         (->> (sqlite/query helpers/*db-path* ["PRAGMA table_info(tasks)"])
              (sort-by :cid)
              (mapv :name)))))

(deftest fresh-database-has-four-indexes
  (is (= #{"tasks_parent_idx" "tasks_status_idx" "tasks_created_at_idx" "tasks_finish_date_idx"}
         (names-of helpers/*db-path* "index"))))

(deftest fresh-database-has-three-fts-triggers
  (is (= #{"tasks_fts_ai" "tasks_fts_ad" "tasks_fts_au"}
         (names-of helpers/*db-path* "trigger"))))

(deftest fresh-database-has-the-fts-table
  (is (contains? (names-of helpers/*db-path* "table") "tasks_fts")))

(deftest fts-match-finds-an-inserted-task
  (sqlite/execute! helpers/*db-path*
                   ["INSERT INTO tasks (title, note, status, created_at) VALUES (?, ?, ?, ?)"
                    "Write quarterly report" nil "todo" "2026-09-28T12:00:00"])
  (is (= [{:rowid 1}]
         (sqlite/query helpers/*db-path*
                       ["SELECT rowid FROM tasks_fts WHERE tasks_fts MATCH ?" "quarterly*"]))))

(defn- fts-rowids [match]
  (sqlite/query helpers/*db-path*
                ["SELECT rowid FROM tasks_fts WHERE tasks_fts MATCH ?" match]))

(deftest fts-match-folds-diacritics
  (let [viet (sqlite.task/insert! (gen.task/task {:id nil :title "Việt Nam"}) helpers/*db-path*)
        auth (sqlite.task/insert! (gen.task/task {:id nil :title "Autenticação"}) helpers/*db-path*)]
    (is (= [{:rowid viet}] (fts-rowids "\"viet\"*")))
    (is (= [{:rowid auth}] (fts-rowids "\"autenticacao\"*")))))

(deftest fts-index-follows-a-status-only-update
  (let [id (sqlite.task/insert! (gen.task/task {:id nil :title "Write quarterly report"}) helpers/*db-path*)]
    (sqlite.task/update! (gen.task/task {:id id :title "Write quarterly report" :status :doing})
                         helpers/*db-path*)
    (is (= [{:rowid id}] (fts-rowids "\"quarterly\"*")))))

(deftest fts-index-follows-a-title-update
  (let [id (sqlite.task/insert! (gen.task/task {:id nil :title "Write quarterly report"}) helpers/*db-path*)]
    (sqlite.task/update! (gen.task/task {:id id :title "Write annual summary"}) helpers/*db-path*)
    (is (= [] (fts-rowids "\"quarterly\"*")))
    (is (= [{:rowid id}] (fts-rowids "\"annual\"*")))))

(deftest ensure-schema-twice-is-a-no-op
  (let [before (snapshot helpers/*db-path*)]
    (is (seq before))
    (db/ensure-schema! helpers/*db-path*)
    (is (= before (snapshot helpers/*db-path*)))
    (is (= 1 (user-version helpers/*db-path*)))))

(deftest ensure-schema-creates-the-parent-directory
  (let [dir  (fs/create-temp-dir {:prefix "knope-test-"})
        path (str (fs/path dir "nested" "deeper" "tasks.db"))]
    (try
      (db/ensure-schema! path)
      (is (= 1 (user-version path)))
      (finally
        (fs/delete-tree dir)))))

(deftest ensure-schema-repairs-an-interrupted-run
  (testing "a database left at user_version 0 with only some objects is completed"
    (let [dir  (fs/create-temp-dir {:prefix "knope-test-"})
          path (str (fs/path dir "tasks.db"))]
      (try
        (sqlite/execute! path ["CREATE TABLE IF NOT EXISTS tasks (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  parent_id   INTEGER REFERENCES tasks(id),
  title       TEXT NOT NULL,
  note        TEXT,
  status      TEXT NOT NULL DEFAULT 'todo',
  created_at  TEXT NOT NULL,
  due_date    TEXT,
  finish_date TEXT
)"])
        (db/ensure-schema! path)
        (is (= 1 (user-version path)))
        (is (= #{"tasks_parent_idx" "tasks_status_idx" "tasks_created_at_idx" "tasks_finish_date_idx"}
               (names-of path "index")))
        (is (= #{"tasks_fts_ai" "tasks_fts_ad" "tasks_fts_au"}
               (names-of path "trigger")))
        (is (contains? (names-of path "table") "tasks_fts"))
        (finally
          (fs/delete-tree dir))))))
