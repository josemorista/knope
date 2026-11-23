(ns knope.diplomat.cli.messages-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [knope.diplomat.cli.messages :as messages]))

(def ^:private reference-keys #{:cli/usage :cli/version :list/more})

(def ^:private sample-details
  {:task/added            {:id 1}
   :task/edited           {:id 1}
   :task/removed          {:id 1 :subtasks 0}
   :update/updated        {:from "1.0.0" :to "1.1.0"}
   :update/current        {:version "1.0.0"}
   :cli/version           {:version "1.0.0"}
   :day/empty             {:date "2026-09-20"}
   :list/more             {:all? false :page 1}})

(defn- details-for [key]
  (or (get sample-details key)
      (when (= "transition" (namespace key)) {:id 1})
      ;; Error instructions read these details.
      {:id 1 :count 1 :subtasks 1 :status :done :input "x" :option "due"
       :path "/tmp/x" :err "boom"}))

(defn- text-ok?
  "A variant is a non-blank string, or a function of the details map that
  returns a non-blank string."
  [key variant]
  (let [text (if (fn? variant) (variant (details-for key)) variant)]
    (and (string? text) (not (str/blank? text)))))

(defn- source-error-codes
  "Every :error/code keyword written in src."
  []
  (->> (fs/glob "src" "**/*.clj")
       (mapcat #(re-seq #":error/code\s+(:[\w.-]+/[\w.-]+)" (slurp (str %))))
       (map (comp keyword #(subs % 1) second))
       set))

(deftest every-error-code-written-in-the-source-has-an-entry
  (let [codes (source-error-codes)]
    (is (>= (count codes) 29) "the scan finds the codes")
    (doseq [code codes]
      (testing (str code)
        (is (contains? messages/entries code))))))

(deftest every-error-has-one-variant-and-a-leslie-line
  (doseq [code (source-error-codes)]
    (testing (str code)
      (let [entry (get messages/entries code)]
        (is (= 1 (count (:variants entry))))
        (is (every? #(text-ok? code %) (:variants entry)))
        (is (not (str/blank? (:leslie entry))))))))

(deftest reference-entries-have-one-non-blank-variant-and-no-leslie-line
  (doseq [key reference-keys]
    (testing (str key)
      (let [entry (get messages/entries key)]
        (is (= 1 (count (:variants entry))))
        (is (every? #(text-ok? key %) (:variants entry)))
        (is (nil? (:leslie entry)))))))

(deftest every-other-message-has-three-non-blank-variants
  (let [errors (source-error-codes)]
    (doseq [[key entry] messages/entries
            :when (not (or (contains? errors key) (contains? reference-keys key)))]
      (testing (str key)
        (is (= 3 (count (:variants entry))))
        (is (every? #(text-ok? key %) (:variants entry))
            "each variant is a non-blank string or a function returning one")
        (is (nil? (:leslie entry)))))))

(deftest say-gives-an-error-its-instruction-then-the-leslie-line
  (is (= "Task 99 not found.\nI looked everywhere, even in the Parks basement."
         (messages/say :task/not-found {:id 99}))))
