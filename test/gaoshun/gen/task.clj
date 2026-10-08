(ns gaoshun.gen.task
  (:require [gaoshun.logic.task :as logic.task])
  (:import (java.time LocalDate LocalDateTime)))

(def default-created-at (LocalDateTime/of 2026 9 28 12 0 0))
(def default-finish-date (LocalDate/of 2026 9 28))

(defn- closed? [status]
  (contains? #{:done :dropped} status))

(defn task
  "A valid task built through make-task. Overrides are merged over the defaults;
  finish-date follows the status unless an override sets it."
  ([] (task {}))
  ([overrides]
   (let [base   {:id          1
                 :parent-id   nil
                 :title       "Write report"
                 :note        nil
                 :status      :todo
                 :created-at  default-created-at
                 :due-date    nil
                 :finish-date nil}
         merged (merge base overrides)]
     (logic.task/make-task
      (if (contains? overrides :finish-date)
        merged
        (assoc merged :finish-date (when (closed? (:status merged))
                                     default-finish-date)))))))
