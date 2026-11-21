(ns knope.logic.sync-test
  (:require [clojure.test :refer [deftest is testing]]
            [knope.logic.sync :as logic.sync]))

(def synced
  {:journal?           false
   :remote-commit      "c1"
   :synced-commit      "c1"
   :fingerprint        "f1"
   :synced-fingerprint "f1"
   :remote-has-file?   true
   :local-has-tasks?   true
   :force?             false})

(def never-synced
  (assoc synced :synced-commit nil :synced-fingerprint nil))

(def remote-ahead (assoc synced :remote-commit "c2"))
(def local-edited (assoc synced :fingerprint "f2"))
(def both-changed (assoc synced :remote-commit "c2" :fingerprint "f2"))

(defn- forced [obs] (assoc obs :force? true))

(deftest push-with-nothing-changed-on-either-side-is-in-sync
  (is (= :in-sync (logic.sync/push-action synced))))

(deftest push-in-sync-stays-in-sync-with-force
  (is (= :in-sync (logic.sync/push-action (forced synced)))))

(deftest push-uploads-when-only-the-local-database-changed
  (is (= :push (logic.sync/push-action local-edited)))
  (is (= :push (logic.sync/push-action (forced local-edited)))))

(deftest push-is-refused-when-the-remote-moved-on
  (is (= :remote-changed (logic.sync/push-action remote-ahead)))
  (is (= :remote-changed (logic.sync/push-action both-changed))))

(deftest push-with-force-goes-ahead-when-the-remote-moved-on
  (is (= :push (logic.sync/push-action (forced remote-ahead))))
  (is (= :push (logic.sync/push-action (forced both-changed)))))

(deftest first-push-to-a-remote-with-no-knope-file-uploads
  (is (= :push (logic.sync/push-action (assoc never-synced :remote-has-file? false)))))

(deftest first-push-to-a-remote-that-holds-a-knope-file-is-refused
  (is (= :remote-changed (logic.sync/push-action never-synced))))

(deftest first-push-over-a-knope-file-goes-ahead-with-force
  (is (= :push (logic.sync/push-action (forced never-synced)))))

(deftest push-is-refused-while-a-journal-sits-next-to-the-database
  (testing "whatever else is true"
    (doseq [obs [synced local-edited remote-ahead never-synced
                 (assoc never-synced :remote-has-file? false)]]
      (is (= :journal-present (logic.sync/push-action (assoc obs :journal? true))))
      (is (= :journal-present (logic.sync/push-action (assoc (forced obs) :journal? true)))))))

(deftest pull-when-the-remote-has-nothing-new-says-so
  (is (= :nothing-new (logic.sync/pull-action synced))))

(deftest pull-has-nothing-new-even-when-the-local-database-changed
  (is (= :nothing-new (logic.sync/pull-action local-edited)))
  (is (= :nothing-new (logic.sync/pull-action (forced local-edited)))))

(deftest pull-replaces-an-unchanged-database-when-the-remote-moved-on
  (is (= :pull (logic.sync/pull-action remote-ahead))))

(deftest pull-is-refused-when-the-local-database-has-unpushed-changes
  (is (= :local-changed (logic.sync/pull-action both-changed))))

(deftest pull-with-force-replaces-a-changed-local-database
  (is (= :pull (logic.sync/pull-action (forced both-changed)))))

(deftest first-pull-onto-a-database-with-no-tasks-pulls
  (is (= :pull (logic.sync/pull-action (assoc never-synced :remote-commit "c2" :local-has-tasks? false)))))

(deftest first-pull-onto-a-database-with-tasks-is-refused
  (is (= :first-pull-over-tasks
         (logic.sync/pull-action (assoc never-synced :remote-commit "c2")))))

(deftest first-pull-over-tasks-goes-ahead-with-force
  (is (= :pull (logic.sync/pull-action (forced (assoc never-synced :remote-commit "c2"))))))

(deftest pull-from-a-remote-with-no-knope-file-has-nothing-to-pull
  (testing "whatever the local state"
    (doseq [obs [synced remote-ahead both-changed never-synced]]
      (is (= :nothing-to-pull (logic.sync/pull-action (assoc obs :remote-has-file? false))))
      (is (= :nothing-to-pull (logic.sync/pull-action (assoc (forced obs) :remote-has-file? false)))))))

(deftest pull-is-refused-while-a-journal-sits-next-to-the-database
  (testing "whatever else is true"
    (doseq [obs [synced remote-ahead both-changed never-synced
                 (assoc synced :remote-has-file? false)]]
      (is (= :journal-present (logic.sync/pull-action (assoc obs :journal? true))))
      (is (= :journal-present (logic.sync/pull-action (assoc (forced obs) :journal? true)))))))
