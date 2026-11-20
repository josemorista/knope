(ns knope.logic.task
  (:require [clojure.string :as str])
  (:import (java.time.temporal ChronoUnit)))

(def ^:private statuses #{:todo :doing :done :dropped})

(defn- closed? [status]
  (contains? #{:done :dropped} status))

(defn- invariant! [message attrs]
  (throw (ex-info message {:type :knope/invariant :attrs attrs})))

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

(defn apply-edit
  [task changes]
  (make-task (merge task changes)))

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

(defn progress
  [children]
  (let [planned (remove #(= :dropped (:status %)) children)]
    (when (seq planned)
      {:done    (count (filter #(= :done (:status %)) planned))
       :planned (count planned)})))

(defn- open? [task]
  (not (closed? (:status task))))

(defn- tree-entry
  [all? root children]
  (let [kids (sort compare-tasks (if all? children (filter open? children)))]
    (cond-> {:task     root
             :children (vec kids)
             :progress (progress children)}
      (and (not all?) (closed? (:status root))) (assoc :context? true))))

(defn build-tree
  [tasks {:keys [all?]}]
  (let [children-of (group-by :parent-id (remove #(nil? (:parent-id %)) tasks))]
    (->> tasks
         (filter #(nil? (:parent-id %)))
         (filter #(or all? (open? %) (some open? (children-of (:id %)))))
         (sort compare-tasks)
         (mapv #(tree-entry all? % (children-of (:id %)))))))

(defn day-sections
  "Splits the tasks recorded for day into {:done :dropped :open}, each sorted
  by id. Done and dropped are the tasks finished on day; open is every task
  created by the end of day and not finished on or before it, so a task
  finished after day reads as open on it. Anything else is left out."
  [tasks day]
  (let [by-id         (fn [pred] (vec (sort-by :id (filter pred tasks))))
        finished-on?  (fn [status] #(and (= status (:status %)) (= day (:finish-date %))))
        day-end       (.atStartOfDay (.plusDays day 1))]
    {:done    (by-id (finished-on? :done))
     :dropped (by-id (finished-on? :dropped))
     :open    (by-id #(and (.isBefore (:created-at %) day-end)
                           (or (nil? (:finish-date %)) (.isAfter (:finish-date %) day))))}))

(defn page-roots
  "Tasks fetched with one root past page-size: drops that root and its children
  and reports whether it existed."
  [tasks page-size]
  (let [extra (:id (nth (remove :parent-id tasks) page-size nil))]
    {:tasks (vec (remove #(and extra (or (= extra (:id %)) (= extra (:parent-id %)))) tasks))
     :more? (some? extra)}))

(defn parent-error
  [parent-id parent]
  (cond
    (nil? parent)
    {:error/code :task/not-found :error/details {:id parent-id}}

    (:parent-id parent)
    {:error/code :task/nesting-too-deep :error/details {:id parent-id}}))

(def ^:private transitions
  {:start  {:to :doing   :applied #{:todo}          :unchanged #{:doing}}
   :done   {:to :done    :applied #{:todo :doing}   :unchanged #{:done}}
   :drop   {:to :dropped :applied #{:todo :doing}   :unchanged #{:dropped}}
   :reopen {:to :todo    :applied #{:done :dropped} :unchanged #{:todo :doing}}})

(defn transition
  "Applies command to task. Returns {:outcome :applied :task t},
  {:outcome :unchanged :task t} (nothing to write), or an illegal-transition
  error."
  [{:keys [id status] :as task} command today]
  (let [{:keys [to applied unchanged]} (transitions command)]
    (cond
      (contains? applied status)
      {:outcome :applied
       :task    (make-task (assoc task
                                  :status      to
                                  :finish-date (when (closed? to) today)))}

      (contains? unchanged status)
      {:outcome :unchanged :task task}

      :else
      {:error/code :task/illegal-transition :error/details {:id id :status status}})))
