(ns knope.helpers
  (:require [babashka.fs :as fs]
            [knope.components :as components])
  (:import (java.time LocalDateTime)))

(def fixed-now (LocalDateTime/of 2026 9 28 12 0 0))

(def ^:dynamic *db-path* nil)
(def ^:dynamic *components* nil)

(defn with-components
  "Builds components through knope.components/components with KNOPE_DB
  pointing at a temp file, then replaces the clock with a constant."
  [f]
  (let [dir  (fs/create-temp-dir {:prefix "knope-test-"})
        path (str (fs/path dir "tasks.db"))]
    (try
      (binding [*db-path*    path
                *components* (assoc (components/components {"KNOPE_DB" path})
                                    :clock (constantly fixed-now))]
        (f))
      (finally
        (fs/delete-tree dir)))))
