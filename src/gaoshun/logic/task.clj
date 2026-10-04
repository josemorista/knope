(ns gaoshun.logic.task
  (:require [clojure.string :as str])
  (:import (java.time.temporal ChronoUnit)))

(def ^:private statuses #{:todo :doing :done :dropped})

(defn- closed? [status]
  (contains? #{:done :dropped} status))

(defn- invariant! [message attrs]
  (throw (ex-info message {:type :gaoshun/invariant :attrs attrs})))

(defn make-task
  [{:keys [id parent-id title note status created-at due-date finish-date] :as attrs}]
  (when-not (contains? statuses status)
    (invariant! "Unknown task status" attrs))
  (when (str/blank? title)
    (invariant! "A task needs a title" attrs))
  (when (not= (closed? status) (some? finish-date))
    (invariant! "Finish date must be present exactly when the task is closed" attrs))
  {:id          id
   :parent-id   parent-id
   :title       title
   :note        note
   :status      status
   :created-at  (.truncatedTo created-at ChronoUnit/SECONDS)
   :due-date    due-date
   :finish-date finish-date})

(defn new-task
  [{:keys [parent-id title note due-date]} now]
  (make-task {:id          nil
              :parent-id   parent-id
              :title       title
              :note        note
              :status      :todo
              :created-at  now
              :due-date    due-date
              :finish-date nil}))

(defn- compare-tasks
  "Open before closed; open by due date ascending with undated last, then id;
  closed by finish date descending, then id descending."
  [a b]
  (let [a-closed? (closed? (:status a))
        b-closed? (closed? (:status b))]
    (cond
      (not= a-closed? b-closed?) (if a-closed? 1 -1)
      a-closed? (compare [(:finish-date b) (:id b)] [(:finish-date a) (:id a)])
      :else     (compare [(nil? (:due-date a)) (:due-date a) (:id a)]
                         [(nil? (:due-date b)) (:due-date b) (:id b)]))))

(defn build-tree
  [tasks {:keys [all?]}]
  (->> tasks
       (filter #(or all? (not (closed? (:status %)))))
       (sort compare-tasks)
       (mapv (fn [task] {:task task}))))
