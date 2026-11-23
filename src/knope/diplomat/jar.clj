(ns knope.diplomat.jar
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio.file CopyOption Files StandardCopyOption)))

(defn running
  "The real path of the jar that url points into, or nil when url is not
  inside a jar. The path is cut out as text, not parsed as a URI, because the
  JVM leaves spaces in it unencoded."
  [url]
  (when-let [[_ path] (re-matches #"jar:file:(.*?)!/.*" (str url))]
    {:path (str (fs/real-path path))}))

(defn version
  "The version the build packed into the jar, or nil when it packed none."
  []
  (some-> (io/resource "knope-version") slurp str/trim))

(defn- not-writable [jar-path]
  {:error/code :update/not-writable :error/details {:path (str jar-path)}})

(defn temp-beside!
  "A new temp file in the jar's folder, so the final move stays on one file
  system and can be atomic."
  [jar-path]
  (try
    (str (fs/create-temp-file {:dir (fs/parent jar-path) :prefix "knope-" :suffix ".jar.tmp"}))
    (catch java.io.IOException _
      (not-writable jar-path))))

(defn replace!
  "Moves temp over the jar with the jar's mode. On failure temp is deleted and
  the jar is left as it was. Files/move rather than fs/move, which would move
  temp into jar-path if that were a folder."
  [temp jar-path]
  (try
    (fs/set-posix-file-permissions temp (fs/posix-file-permissions jar-path))
    (Files/move (fs/path temp) (fs/path jar-path)
                (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
    nil
    (catch java.io.IOException _
      (fs/delete-if-exists temp)
      (not-writable jar-path))))

(defn discard!
  [temp]
  (fs/delete-if-exists temp)
  nil)
