(ns gaoshun.controllers.task
  (:require [gaoshun.diplomat.sqlite.task :as sqlite.task]
            [gaoshun.logic.task :as logic.task]))

(defn add!
  [attrs {:keys [sqlite clock]}]
  (let [task (logic.task/new-task attrs (clock))
        id   (sqlite.task/insert! task sqlite)]
    (assoc task :id id)))
