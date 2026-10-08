(ns gaoshun.controllers.task
  (:require [gaoshun.diplomat.sqlite.task :as sqlite.task]
            [gaoshun.logic.task :as logic.task]))

(defn add!
  [attrs {:keys [sqlite clock]}]
  (let [task (logic.task/new-task attrs (clock))
        id   (sqlite.task/insert! task sqlite)]
    (assoc task :id id)))

(def ^:private page-size 50)

(defn list-tasks
  "One page of the tree, and whether another page follows. Fetches one row past
  the page to answer that without a COUNT."
  [all? page {:keys [sqlite]}]
  (let [list-fn (if all? sqlite.task/list-all sqlite.task/list-open)
        tasks   (list-fn (inc page-size) (* page page-size) sqlite)]
    {:tree  (logic.task/build-tree (take page-size tasks) {:all? all?})
     :more? (> (count tasks) page-size)}))
