(ns knope.components
  (:require [babashka.fs :as fs]
            [knope.diplomat.sqlite.db :as db])
  (:import (java.time LocalDateTime)))

(defn- db-path [env]
  (if-let [path (not-empty (get env "KNOPE_DB"))]
    (str (fs/normalize (fs/absolutize path)))
    (str (fs/path (get env "HOME") ".knope" "tasks.db"))))

(defn- read-passphrase
  [prompt]
  (when-let [console (System/console)]
    (some-> (.readPassword console prompt (object-array 0)) String.)))

(defn components
  [env]
  (let [path (db-path env)]
    (db/ensure-schema! path)
    {:sqlite          path
     :clock           (fn [] (LocalDateTime/now))
     :read-passphrase read-passphrase
     :kdf-iterations  600000}))
