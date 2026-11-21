(ns knope.helpers
  (:require [babashka.fs :as fs]
            [knope.components :as components])
  (:import (java.time LocalDateTime)))

(def fixed-now (LocalDateTime/of 2026 9 28 12 0 0))

(def test-kdf-iterations
  "Key-derivation cost used by tests, so encrypting stays fast."
  1000)

(def ^:dynamic *db-path* nil)
(def ^:dynamic *components* nil)

(defn scripted-reader
  "Returns [reader asked]. reader returns nil once answers run out, as with no
  terminal; asked is an atom of every prompt."
  [answers]
  (let [remaining (atom (seq answers))
        asked     (atom [])]
    [(fn [prompt]
       (swap! asked conj prompt)
       (let [answer (first @remaining)]
         (swap! remaining next)
         answer))
     asked]))

(defn test-components
  [path]
  (assoc (components/components {"KNOPE_DB" path})
         :clock           (constantly fixed-now)
         :kdf-iterations  test-kdf-iterations
         :read-passphrase (constantly nil)))

(defn with-components
  "Builds components through knope.components/components with KNOPE_DB
  pointing at a temp file, then replaces the clock with a constant."
  [f]
  (let [dir  (fs/create-temp-dir {:prefix "knope-test-"})
        path (str (fs/path dir "tasks.db"))]
    (try
      (binding [*db-path*    path
                *components* (test-components path)]
        (f))
      (finally
        (fs/delete-tree dir)))))
