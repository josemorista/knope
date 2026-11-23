(ns knope.diplomat.jar-test
  (:require [babashka.classpath :as classpath]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knope.diplomat.jar :as jar])
  (:import (java.net URL)))

(def ^:private cli-resource "knope/diplomat/cli.clj")

(defn- jar-url
  "The URL of a knope source file inside the jar at jar-path, with the path
  left unencoded as the JVM reports it."
  [jar-path]
  (URL. (str "jar:file:" jar-path "!/" cli-resource)))

(defn- with-temp-dir [f]
  (let [dir (fs/create-temp-dir {:prefix "knope-jar-test-"})]
    (try
      (f dir)
      (finally
        (fs/delete-tree dir)))))

(deftest running-gives-the-path-of-the-jar-a-jar-url-points-into
  (with-temp-dir
    (fn [dir]
      (let [jar-file (fs/path dir "knope.jar")]
        (spit (str jar-file) "")
        (is (= {:path (str (fs/real-path jar-file))}
               (jar/running (jar-url jar-file))))))))

(deftest running-keeps-a-space-in-the-jar-path
  (with-temp-dir
    (fn [dir]
      (let [folder   (fs/create-dir (fs/path dir "my tools"))
            jar-file (fs/path folder "knope.jar")]
        (spit (str jar-file) "")
        (is (= {:path (str (fs/real-path jar-file))}
               (jar/running (jar-url jar-file))))))))

(deftest running-follows-a-symlinked-jar-to-its-target
  (with-temp-dir
    (fn [dir]
      (let [real-dir (fs/create-dir (fs/path dir "real"))
            link-dir (fs/create-dir (fs/path dir "bin"))
            target   (fs/path real-dir "knope.jar")
            link     (fs/path link-dir "knope.jar")]
        (spit (str target) "")
        (fs/create-sym-link link target)
        (is (= {:path (str (fs/real-path target))}
               (jar/running (jar-url link))))))))

(deftest running-is-nil-outside-a-jar
  (testing "the source file of a checkout is a file: URL"
    (let [url (io/resource cli-resource)]
      (is (= "file" (.getProtocol ^URL url)))
      (is (nil? (jar/running url)))))
  (testing "a file: URL that only looks like a jar"
    (is (nil? (jar/running (URL. "file:/tmp/knope.jar"))))))

(deftest version-is-nil-without-the-resource-and-the-trimmed-text-with-it
  (testing "a checkout has no knope-version resource"
    (is (nil? (jar/version))))
  (testing "the resource text is returned without its trailing newline"
    (with-temp-dir
      (fn [dir]
        (spit (str (fs/path dir "knope-version")) "1.2.3\n")
        (classpath/add-classpath (str dir))
        (is (= "1.2.3" (jar/version)))))))

(defn- tmp-files
  "The names of the knope temp files in dir."
  [dir]
  (->> (fs/list-dir dir)
       (map (comp str fs/file-name))
       (filter #(and (str/starts-with? % "knope-") (str/ends-with? % ".jar.tmp")))
       sort))

(defn- mode [path]
  (fs/posix-file-permissions path))

(deftest temp-beside-creates-a-knope-temp-file-in-the-folder-of-the-jar
  (with-temp-dir
    (fn [dir]
      (let [jar-file (fs/path dir "knope.jar")]
        (spit (str jar-file) "old")
        (let [temp (jar/temp-beside! (str jar-file))]
          (is (string? (str temp)))
          (is (fs/exists? temp))
          (is (= (str (fs/real-path dir)) (str (fs/real-path (fs/parent temp)))))
          (is (str/starts-with? (str (fs/file-name temp)) "knope-"))
          (is (str/ends-with? (str (fs/file-name temp)) ".jar.tmp")))))))

(deftest temp-beside-in-a-read-only-folder-is-not-writable
  (with-temp-dir
    (fn [dir]
      (let [folder   (fs/create-dir (fs/path dir "locked"))
            jar-file (fs/path folder "knope.jar")]
        (spit (str jar-file) "old")
        (fs/set-posix-file-permissions folder "r-xr-xr-x")
        (try
          (let [result (jar/temp-beside! (str jar-file))]
            (is (= :update/not-writable (:error/code result)))
            (is (= ["knope.jar"] (map (comp str fs/file-name) (fs/list-dir folder)))))
          (finally
            (fs/set-posix-file-permissions folder "rwxr-xr-x")))))))

(deftest replace-puts-the-new-bytes-in-place-and-keeps-the-old-mode
  (with-temp-dir
    (fn [dir]
      (let [jar-file (fs/path dir "knope.jar")]
        (spit (str jar-file) "old jar")
        (fs/set-posix-file-permissions jar-file "rwxr-xr-x")
        (let [temp (jar/temp-beside! (str jar-file))]
          (spit (str temp) "new jar")
          (is (nil? (jar/replace! (str temp) (str jar-file))))
          (is (= "new jar" (slurp (str jar-file))))
          (is (= "rwxr-xr-x" (fs/posix->str (mode jar-file))))
          (is (not (fs/exists? temp)))
          (is (= [] (tmp-files dir))))))))

(deftest replace-keeps-a-restrictive-mode-too
  (with-temp-dir
    (fn [dir]
      (let [jar-file (fs/path dir "knope.jar")]
        (spit (str jar-file) "old jar")
        (fs/set-posix-file-permissions jar-file "rw-r-----")
        (let [temp (jar/temp-beside! (str jar-file))]
          (spit (str temp) "new jar")
          (jar/replace! (str temp) (str jar-file))
          (is (= "rw-r-----" (fs/posix->str (mode jar-file)))))))))

(deftest replace-writes-through-a-symlink-and-the-link-stays-a-link
  (with-temp-dir
    (fn [dir]
      (let [real-dir (fs/create-dir (fs/path dir "real"))
            link-dir (fs/create-dir (fs/path dir "bin"))
            target   (fs/path real-dir "knope.jar")
            link     (fs/path link-dir "knope.jar")]
        (spit (str target) "old jar")
        (fs/create-sym-link link target)
        (let [{:keys [path]} (jar/running (jar-url link))
              temp           (jar/temp-beside! path)]
          (is (= (str (fs/real-path real-dir)) (str (fs/real-path (fs/parent temp)))))
          (spit (str temp) "new jar")
          (is (nil? (jar/replace! (str temp) path)))
          (is (fs/sym-link? link))
          (is (= "new jar" (slurp (str target))))
          (is (= "new jar" (slurp (str link))))
          (is (= [] (tmp-files link-dir)))
          (is (= [] (tmp-files real-dir))))))))

(deftest a-failed-replace-leaves-no-temp-file-and-reports-not-writable
  (with-temp-dir
    (fn [dir]
      (let [blocked  (fs/create-dir (fs/path dir "knope.jar"))
            temp-in  (fs/path dir "knope-failed.jar.tmp")]
        (spit (str (fs/path blocked "inside")) "x")
        (spit (str temp-in) "new jar")
        (let [result (jar/replace! (str temp-in) (str blocked))]
          (is (= :update/not-writable (:error/code result)))
          (is (not (fs/exists? temp-in)))
          (is (= [] (tmp-files dir)))
          (is (= "x" (slurp (str (fs/path blocked "inside"))))))))))

(deftest discard-removes-the-temp-file-and-leaves-the-jar
  (with-temp-dir
    (fn [dir]
      (let [jar-file (fs/path dir "knope.jar")]
        (spit (str jar-file) "old jar")
        (let [temp (jar/temp-beside! (str jar-file))]
          (jar/discard! (str temp))
          (is (not (fs/exists? temp)))
          (is (= [] (tmp-files dir)))
          (is (= "old jar" (slurp (str jar-file)))))))))
