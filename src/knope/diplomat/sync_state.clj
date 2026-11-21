(ns knope.diplomat.sync-state
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [knope.diplomat.sqlite.db :as sqlite.db])
  (:import (java.security MessageDigest)
           (java.util HexFormat)))

(defn sync-dir
  [db-path]
  (str db-path ".sync"))

(defn clone-dir
  [db-path]
  (str (fs/path (sync-dir db-path) "clone")))

(defn clone-file
  [db-path name]
  (str (fs/path (clone-dir db-path) name)))

(defn- state-file [db-path]
  (str (fs/path (sync-dir db-path) "state.edn")))

(defn read-state
  "{:remote url :commit sha :fingerprint hex}, or nil when no remote was set."
  [db-path]
  (let [file (state-file db-path)]
    (when (fs/exists? file)
      (edn/read-string (slurp file)))))

(defn write-state!
  "Temp file then rename, so a crash never leaves the state half-written."
  [db-path state]
  (let [dir (fs/create-dirs (sync-dir db-path))
        tmp (fs/create-temp-file {:dir dir :prefix "state." :suffix ".edn"})]
    (spit (str tmp) (pr-str state))
    (fs/move tmp (state-file db-path) {:replace-existing true :atomic-move true})
    nil))

(defn reset-for-remote!
  [db-path url]
  (when (fs/exists? (clone-dir db-path))
    (fs/delete-tree (clone-dir db-path)))
  (write-state! db-path {:remote url}))

(defn bytes-fingerprint
  [bytes]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes)))

(defn fingerprint
  [db-path]
  (bytes-fingerprint (fs/read-all-bytes db-path)))

(defn journal-path
  [db-path]
  (str db-path "-journal"))

(defn journal?
  [db-path]
  (fs/exists? (journal-path db-path)))

(defn cloned?
  [db-path]
  (fs/exists? (clone-dir db-path)))

(defn link?
  [path]
  (fs/sym-link? path))

(defn file?
  [path]
  (fs/exists? path))

(defn read-file
  [path]
  (fs/read-all-bytes path))

(defn write-file!
  [path bytes]
  (fs/write-bytes path bytes))

(defn snapshot-bytes!
  [db-path]
  (let [snapshot (str (fs/path (sync-dir db-path) "push.db"))]
    (try
      (fs/delete-if-exists snapshot)
      (sqlite.db/snapshot! db-path snapshot)
      (fs/read-all-bytes snapshot)
      (finally
        (fs/delete-if-exists snapshot)))))

(defn replace-database!
  "Temp file then rename, so a crash never leaves the database half-written."
  [db-path plain force?]
  (let [tmp (fs/create-temp-file {:dir    (fs/parent db-path)
                                  :prefix (str (fs/file-name db-path) ".")
                                  :suffix ".pull"})]
    (try
      (fs/write-bytes tmp plain)
      (when force?
        (let [backup (str (fs/path (sync-dir db-path) "before-pull.db"))]
          (fs/delete-if-exists backup)
          (sqlite.db/snapshot! db-path backup)))
      (fs/move tmp db-path {:replace-existing true :atomic-move true})
      (finally
        (fs/delete-if-exists tmp)))))
