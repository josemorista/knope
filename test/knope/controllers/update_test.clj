(ns knope.controllers.update-test
  (:require [clojure.test :refer [deftest is testing]]
            [knope.controllers.update :as controllers.update]))

(defn- fakes
  "Components with fake :release and :jar maps. Every call lands in the calls
  atom as [name & args]. Overrides replace single functions by name."
  [calls {:keys [running version latest-tag temp-beside! download! replace!]
          :or   {running      {:path "/opt/knope/knope.jar"}
                 version      "1.0.0"
                 latest-tag   {:ok "v1.1.0"}
                 temp-beside! "/opt/knope/knope-1.jar.tmp"}}]
  (let [record (fn [name result]
                 (fn [& args]
                   (swap! calls conj (into [name] args))
                   (if (fn? result) (apply result args) result)))]
    {:release {:latest-tag (record :latest-tag latest-tag)
               :download!  (record :download! download!)}
     :jar     {:running      (record :running running)
               :version      (record :version version)
               :temp-beside! (record :temp-beside! temp-beside!)
               :replace!     (record :replace! replace!)
               :discard!     (record :discard! nil)}}))

(defn- called [calls name]
  (filter #(= name (first %)) @calls))

(deftest an-older-jar-is-replaced-by-the-latest-release
  (let [calls  (atom [])
        result (controllers.update/update! (fakes calls {}))]
    (is (= {:outcome :updated :from "1.0.0" :to "1.1.0"} result))
    (testing "the release is fetched into the temp file and moved over the jar"
      (is (= [[:download! "v1.1.0" "/opt/knope/knope-1.jar.tmp"]] (called calls :download!)))
      (is (= [[:temp-beside! "/opt/knope/knope.jar"]] (called calls :temp-beside!)))
      (is (= [[:replace! "/opt/knope/knope-1.jar.tmp" "/opt/knope/knope.jar"]]
             (called calls :replace!)))
      (is (empty? (called calls :discard!))))))

(deftest an-unversioned-jar-is-updated
  (let [calls  (atom [])
        result (controllers.update/update! (fakes calls {:version nil}))]
    (is (= {:outcome :updated :from nil :to "1.1.0"} result))
    (is (= 1 (count (called calls :replace!))))))

(deftest outside-a-jar-it-fails-without-any-network-call-or-file
  (let [calls  (atom [])
        result (controllers.update/update! (fakes calls {:running nil}))]
    (is (= :update/not-a-jar (:error/code result)))
    (is (empty? (called calls :latest-tag)))
    (is (empty? (called calls :temp-beside!)))
    (is (empty? (called calls :download!)))
    (is (empty? (called calls :replace!)))))

(deftest an-equal-version-touches-nothing
  (let [calls  (atom [])
        result (controllers.update/update! (fakes calls {:version "1.1.0"}))]
    (is (= {:outcome :up-to-date :version "1.1.0"} result))
    (is (empty? (called calls :temp-beside!)))
    (is (empty? (called calls :download!)))
    (is (empty? (called calls :replace!)))
    (is (empty? (called calls :discard!)))))

(deftest a-newer-installed-version-touches-nothing
  (let [calls  (atom [])
        result (controllers.update/update! (fakes calls {:version "2.0.0"}))]
    (is (= :up-to-date (:outcome result)))
    (is (empty? (called calls :temp-beside!)))
    (is (empty? (called calls :download!)))
    (is (empty? (called calls :replace!)))
    (is (empty? (called calls :discard!)))))

(deftest a-failed-lookup-is-returned-and-nothing-is-written
  (doseq [code [:update/unreachable :update/no-release]]
    (testing (str code)
      (let [calls  (atom [])
            result (controllers.update/update!
                    (fakes calls {:latest-tag {:error/code code}}))]
        (is (= code (:error/code result)))
        (is (empty? (called calls :temp-beside!)))
        (is (empty? (called calls :download!)))))))

(deftest a-latest-tag-that-does-not-parse-is-no-release
  (let [calls  (atom [])
        result (controllers.update/update! (fakes calls {:latest-tag {:ok "nightly"}}))]
    (is (= :update/no-release (:error/code result)))
    (is (empty? (called calls :temp-beside!)))
    (is (empty? (called calls :download!)))))

(deftest a-not-writable-folder-stops-before-the-download
  (let [calls  (atom [])
        result (controllers.update/update!
                (fakes calls {:temp-beside! {:error/code    :update/not-writable
                                             :error/details {:path "/opt/knope/knope.jar"}}}))]
    (is (= :update/not-writable (:error/code result)))
    (is (empty? (called calls :download!)))
    (is (empty? (called calls :replace!)))))

(deftest a-failed-download-discards-the-temp-file-and-keeps-the-jar
  (let [calls  (atom [])
        result (controllers.update/update!
                (fakes calls {:download! {:error/code :update/download-failed}}))]
    (is (= :update/download-failed (:error/code result)))
    (is (= [[:discard! "/opt/knope/knope-1.jar.tmp"]] (called calls :discard!)))
    (is (empty? (called calls :replace!)))))

(deftest a-failed-replace-discards-the-temp-file
  (let [calls  (atom [])
        result (controllers.update/update!
                (fakes calls {:replace! {:error/code :update/not-writable}}))]
    (is (= :update/not-writable (:error/code result)))
    (is (= [[:discard! "/opt/knope/knope-1.jar.tmp"]] (called calls :discard!)))))
