(ns gaoshun.components
  (:require [babashka.fs :as fs]
            [gaoshun.diplomat.sqlite.db :as db])
  (:import (java.time LocalDateTime)))

(defn- db-path [env]
  (or (get env "GAOSHUN_DB")
      (str (fs/path (get env "HOME") ".gaoshun" "tasks.db"))))

(defn components
  [env]
  (let [path (db-path env)]
    (db/ensure-schema! path)
    {:sqlite path
     :clock  (fn [] (LocalDateTime/now))}))
