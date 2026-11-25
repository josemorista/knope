(ns knope.components
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [knope.diplomat.jar :as jar]
            [knope.diplomat.release :as release]
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

(defn lite-components
  "The components that need no database."
  []
  {:clock           (fn [] (LocalDateTime/now))
   :read-passphrase read-passphrase
   :kdf-iterations  600000
   :release         {:latest-tag (partial release/latest-tag release/default-config)
                     :download!  (partial release/download! release/default-config)}
   :jar             {:running      (fn [] (jar/running (io/resource "knope/diplomat/cli.clj")))
                     :version      jar/version
                     :temp-beside! jar/temp-beside!
                     :replace!     jar/replace!
                     :discard!     jar/discard!}})

(defn components
  [env]
  (let [path (db-path env)]
    (db/ensure-schema! path)
    (assoc (lite-components) :sqlite path)))

(defn for-args
  "The components a command needs: update, --version and printing the
  completion script never open the database, and completing words and flags
  opens it only if a candidate needs it."
  [env args]
  (cond
    (#{"update" "--version" "completion"} (first args))
    (lite-components)

    (= "org.babashka.cli/completions" (first args))
    (assoc (lite-components) :sqlite (delay (:sqlite (components env))))

    :else
    (components env)))
