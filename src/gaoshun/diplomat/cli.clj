(ns gaoshun.diplomat.cli
  (:require [babashka.cli :as cli]
            [clojure.string :as str]
            [gaoshun.adapters.task :as adapters.task]
            [gaoshun.components :as components]
            [gaoshun.controllers.task :as controllers.task]
            [gaoshun.diplomat.cli.render :as render]))

(def ^:private usage
  "Usage: gaoshun <command> [options]

Commands:
  add <title> [--due DATE] [--note TEXT] [--parent ID]  Add a task, or a subtask of ID.
  list [--all] [--page N]                               Show pending tasks, 50 per page; --all includes closed ones.
  start ID                                              Mark a task as in progress.
  done ID                                               Mark a task as done.
  drop ID                                               Mark a task as dropped.
  reopen ID                                             Move a done or dropped task back to todo.
")

(defn- error-message [{:error/keys [code details]}]
  (case code
    :task/not-found           (str "Task " (:id details) " not found.")
    :task/nesting-too-deep    (str "Task " (:id details) " is a subtask; subtasks can't have subtasks.")
    :task/illegal-transition  (str "Task " (:id details) " is " (name (:status details)) "; reopen it first.")
    :validation/bad-id        (str "'" (:input details) "' is not a task id.")
    :validation/missing-title "A task needs a title."
    :validation/bad-date      (str "Can't read date '" (:input details)
                                   "'. Use YYYY-MM-DD, today, tomorrow, yesterday, +Nd or -Nd.")
    :validation/bad-page      "--page needs a number."
    :validation/missing-value (str "--" (:option details) " needs a value.")
    :validation/unknown-option (str "Unknown option '--" (:option details) "'.")
    :validation/extra-argument (str "Unexpected argument '" (:input details)
                                    "'. Put text with spaces in quotes.")))

(defn- error-result [error]
  {:exit 1 :out "" :err (str (error-message error) "\n")})

(defn- add [components {:keys [opts]}]
  (let [today (.toLocalDate ((:clock components)))
        attrs (adapters.task/args->new-attrs opts today)]
    (if (:error/code attrs)
      (error-result attrs)
      (let [task (controllers.task/add! attrs components)]
        (if (:error/code task)
          (error-result task)
          {:exit 0 :out (str "Added task " (:id task) ".\n") :err ""})))))

(defn- more-line [all? page]
  (str "More on page " (inc page) ": gaoshun list " (when all? "--all ") "--page " (inc page)))

(defn- list-tasks [components opts {cli-opts :opts}]
  (let [all? (boolean (:all cli-opts))
        page (adapters.task/parse-page (:page cli-opts))]
    (if (:error/code page)
      (error-result page)
      (let [today                (.toLocalDate ((:clock components)))
            {:keys [tree more?]} (controllers.task/list-tasks all? page components)
            entries              (mapv (fn [{:keys [task children progress context?]}]
                                         {:row      (adapters.task/task->display-row
                                                     task today {:progress progress :dimmed? context?})
                                          :children (mapv #(adapters.task/task->display-row % today {})
                                                          children)})
                                       tree)
            lines                (cond-> (render/tree-lines entries opts)
                                   more? (conj (more-line all? page)))]
        {:exit 0 :out (str (str/join "\n" lines) "\n") :err ""}))))

(defn- fallback [{:keys [opts]}]
  (if (:help opts)
    {:exit 0 :out usage :err ""}
    {:exit 1 :out "" :err usage}))

(def ^:private transition-messages
  {:start  {:applied "started" :unchanged "is already in progress"}
   :done   {:applied "marked done" :unchanged "is already done"}
   :drop   {:applied "dropped" :unchanged "is already dropped"}
   :reopen {:applied "reopened" :unchanged "is already open"}})

(defn- transition [components command {:keys [opts] :as parsed}]
  (if-let [id-arg (:id opts)]
    (let [id (adapters.task/parse-id id-arg)]
      (if (:error/code id)
        (error-result id)
        (let [{:keys [outcome task] :as result} (controllers.task/transition! id command components)]
          (if (:error/code result)
            (error-result result)
            {:exit 0 :out (str "Task " (:id task) " " (get-in transition-messages [command outcome]) ".\n") :err ""}))))
    (fallback parsed)))

(defn- option-name [word]
  (when (str/starts-with? word "--")
    (first (str/split (subs word 2) #"=" 2))))

(defn- shape-checked
  "Refuse an option the entry does not define, then a word beyond its
  positional arguments, before the handler sees any value. Options are read
  from the raw words: babashka.cli stores `--title x` under the same key as a
  positional title, and stops reading options at the first extra word."
  [argv {:keys [cmds coerce args->opts] handler :fn :as entry}]
  (let [allowed (apply disj (set (keys coerce)) args->opts)]
    (assoc entry :fn
           (fn [{:keys [opts args] :as parsed}]
             (if-let [option (or (some #(when-let [n (option-name %)]
                                          (when-not (allowed (keyword n)) n))
                                       (drop (count cmds) argv))
                                 (some #(when-not (contains? coerce %) (name %)) (keys opts)))]
               (error-result {:error/code    :validation/unknown-option
                              :error/details {:option option}})
               (if (seq args)
                 (error-result {:error/code    :validation/extra-argument
                                :error/details {:input (first args)}})
                 (handler parsed)))))))

(defn- dispatch [components opts argv]
  (cli/dispatch (conj (mapv (partial shape-checked argv)
                            [{:cmds       ["add"]
                              :fn         (partial add components)
                              :args->opts [:title]
                              :coerce     {:title :string :due :string :note :string :parent :string}}
                             {:cmds   ["list"]
                              :fn     (partial list-tasks components opts)
                              :coerce {:all :boolean :page :string}}
                             {:cmds       ["start"]
                              :fn         (partial transition components :start)
                              :args->opts [:id]
                              :coerce     {:id :string}}
                             {:cmds       ["done"]
                              :fn         (partial transition components :done)
                              :args->opts [:id]
                              :coerce     {:id :string}}
                             {:cmds       ["drop"]
                              :fn         (partial transition components :drop)
                              :args->opts [:id]
                              :coerce     {:id :string}}
                             {:cmds       ["reopen"]
                              :fn         (partial transition components :reopen)
                              :args->opts [:id]
                              :coerce     {:id :string}}])
                      {:cmds [] :fn fallback})
                argv))

(defn- missing-value
  "babashka.cli throws when a value option ends the argv; turn that into our
  error and rethrow anything else."
  [e]
  (let [{:keys [type cause implicit-value option]} (ex-data e)]
    (if (and (= :org.babashka/cli type) (= :coerce cause) implicit-value)
      (error-result {:error/code    :validation/missing-value
                     :error/details {:option (name option)}})
      (throw e))))

(defn run
  [components opts argv]
  (try
    (dispatch components opts argv)
    (catch clojure.lang.ExceptionInfo e
      (missing-value e))))

(defn -main
  [& args]
  (let [env                    (into {} (System/getenv))
        opts                   {:color? (and (= "1" (get env "GAOSHUN_TTY"))
                                             (nil? (get env "NO_COLOR")))}
        {:keys [exit out err]} (run (components/components env) opts (vec args))]
    (print out)
    (flush)
    (binding [*out* *err*]
      (print err)
      (flush))
    (System/exit exit)))
