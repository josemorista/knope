(ns gaoshun.controllers.task
  (:require [gaoshun.diplomat.sqlite.task :as sqlite.task]
            [gaoshun.logic.date :as logic.date]
            [gaoshun.logic.task :as logic.task]))

(defn add!
  [{:keys [parent-id] :as attrs} {:keys [sqlite clock]}]
  (or (when parent-id
        (logic.task/parent-error parent-id (sqlite.task/find-by-id parent-id sqlite)))
      (let [task (logic.task/new-task attrs (clock))
            id   (sqlite.task/insert! task sqlite)]
        (assoc task :id id))))

(defn transition!
  "Moves task id by command. Only an :applied outcome is written, so a repeat
  leaves the stored task, and its finish date, alone."
  [id command {:keys [sqlite clock]}]
  (if-let [task (sqlite.task/find-by-id id sqlite)]
    (let [result (logic.task/transition task command (.toLocalDate ^java.time.LocalDateTime (clock)))]
      (when (= :applied (:outcome result))
        (sqlite.task/update! (:task result) sqlite))
      result)
    {:error/code :task/not-found :error/details {:id id}}))

(defn edit!
  [id changes {:keys [sqlite]}]
  (if-let [task (sqlite.task/find-by-id id sqlite)]
    (let [edited (logic.task/apply-edit task changes)]
      (sqlite.task/update! edited sqlite)
      edited)
    {:error/code :task/not-found :error/details {:id id}}))

(defn remove!
  "Deletes task id. A parent with subtasks is refused unless force?, which
  deletes the family. Returns the id and how many subtasks went with it."
  [id force? {:keys [sqlite]}]
  (if (sqlite.task/find-by-id id sqlite)
    (let [subtasks (sqlite.task/count-children id sqlite)]
      (if (and (pos? subtasks) (not force?))
        {:error/code :task/has-subtasks :error/details {:id id :count subtasks}}
        (do (sqlite.task/delete! id sqlite)
            {:id id :subtasks subtasks})))
    {:error/code :task/not-found :error/details {:id id}}))

(defn day
  "The tasks recorded for date, split into {:done :dropped :open}."
  [date {:keys [sqlite]}]
  (-> (sqlite.task/day-rows date (logic.date/next-day date) sqlite)
      (logic.task/day-sections date)))

(def ^:private page-size 50)

(defn list-tasks
  "One page of the tree, and whether another page follows. Fetches one root past
  the page to answer that without a COUNT."
  [all? page {:keys [sqlite]}]
  (let [list-fn               (if all? sqlite.task/list-all sqlite.task/list-open)
        fetched               (list-fn (inc page-size) (* page page-size) sqlite)
        {:keys [tasks more?]} (logic.task/page-roots fetched page-size)]
    {:tree  (logic.task/build-tree tasks {:all? all?})
     :more? more?}))

(defn search
  "The tasks the FTS match expression finds, most relevant first."
  [match {:keys [sqlite]}]
  (sqlite.task/search match sqlite))
