(ns knope.logic.update-test
  (:require [clojure.test :refer [deftest is testing]]
            [knope.logic.update :as logic.update]))

(deftest parse-version-reads-three-numbers-with-or-without-a-leading-v
  (is (= [1 2 3] (logic.update/parse-version "1.2.3")))
  (is (= [1 2 3] (logic.update/parse-version "v1.2.3")))
  (is (= [10 0 25] (logic.update/parse-version "v10.0.25"))))

(deftest parse-version-is-nil-for-anything-that-is-not-major-minor-patch
  (doseq [input [nil "" "latest" "1.2" "1.2.3.4" "v1.2.x" "1.2.3-rc1" "vv1.2.3"]]
    (testing (pr-str input)
      (is (nil? (logic.update/parse-version input))))))

(deftest action-is-up-to-date-when-the-versions-are-equal
  (is (= :up-to-date (logic.update/action "1.2.3" "v1.2.3")))
  (is (= :up-to-date (logic.update/action "1.2.3" "1.2.3"))))

(deftest action-is-update-when-the-latest-is-newer
  (is (= :update (logic.update/action "1.2.3" "v1.2.4")))
  (is (= :update (logic.update/action "1.2.3" "v1.3.0")))
  (is (= :update (logic.update/action "1.2.3" "v2.0.0")))
  (testing "numbers compare as numbers, not as text"
    (is (= :update (logic.update/action "1.9.0" "v1.10.0")))))

(deftest action-is-up-to-date-when-the-installed-version-is-newer
  (is (= :up-to-date (logic.update/action "1.2.4" "v1.2.3")))
  (is (= :up-to-date (logic.update/action "2.0.0" "v1.9.9")))
  (is (= :up-to-date (logic.update/action "1.10.0" "v1.9.0"))))

(deftest action-is-update-for-an-unversioned-installed-jar
  (is (= :update (logic.update/action nil "v1.0.0"))))

(deftest action-is-update-for-an-unparsable-installed-version
  (is (= :update (logic.update/action "banana" "v1.0.0"))))
