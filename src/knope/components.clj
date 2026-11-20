(ns knope.components
  (:require [babashka.fs :as fs]
            [knope.diplomat.sqlite.db :as db])
  (:import (java.time LocalDateTime)))

(defn- db-path [env]
  (if-let [path (not-empty (get env "KNOPE_DB"))]
    (str (fs/normalize (fs/absolutize path)))
    (str (fs/path (get env "HOME") ".knope" "tasks.db"))))

(defn components
  [env]
  (let [path (db-path env)]
    (db/ensure-schema! path)
    {:sqlite path
     :clock  (fn [] (LocalDateTime/now))}))
