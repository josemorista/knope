(ns knope.diplomat.cli
  (:require [babashka.cli :as cli]
            [babashka.terminal :as terminal]
            [clojure.string :as str]
            [knope.adapters.task :as adapters.task]
            [knope.components :as components]
            [knope.controllers.sync :as controllers.sync]
            [knope.controllers.task :as controllers.task]
            [knope.controllers.update :as controllers.update]
            [knope.diplomat.cli.messages :as messages]
            [knope.diplomat.cli.render :as render]
            [knope.logic.task :as logic.task]))

(defn- error-result [error]
  {:exit 1 :out "" :err (str (messages/say (:error/code error) (:error/details error)) "\n")})

(defn- add [components {:keys [opts]}]
  (let [today (.toLocalDate ((:clock components)))
        attrs (adapters.task/args->new-attrs opts today)]
    (if (:error/code attrs)
      (error-result attrs)
      (let [task (controllers.task/add! attrs components)]
        (if (:error/code task)
          (error-result task)
          {:exit 0 :out (str (messages/say :task/added {:id (:id task)}) "\n") :err ""})))))

(defn- framed
  "The intro, a blank line, the lines, a blank line and the ending, one per
  line; the empty message alone when there are no lines."
  [topic details lines]
  (let [say #(messages/say (keyword (name topic) %) details)]
    (str (str/join "\n" (if (empty? lines)
                          [(say "empty")]
                          (concat [(say "intro") ""] lines ["" (say "ending")])))
         "\n")))

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
                                   more? (conj (messages/say :list/more {:all? all? :page page})))]
        {:exit 0 :out (framed :list {} (when (seq entries) lines)) :err ""}))))

(defn- day [components opts {cli-opts :opts}]
  (let [today (.toLocalDate ((:clock components)))
        date  (adapters.task/args->day cli-opts today)]
    (if (:error/code date)
      (error-result date)
      (let [sections (controllers.task/day date components)
            row      (fn [task options]
                       (-> (logic.task/as-of-day task date)
                           (adapters.task/task->display-row today options)
                           (dissoc :due-label :urgency)))
            entry    (fn [{:keys [task children context?]}]
                       {:row      (row task {:dimmed? context?})
                        :children (mapv #(row % {}) children)})
            lines    (when (some seq (vals sections))
                       (render/section-lines date (update-vals sections #(mapv entry %)) opts))]
        {:exit 0 :out (framed :day {:date (str date)} lines) :err ""}))))

(defn- search [components opts {cli-opts :opts}]
  (let [match (adapters.task/search-match (:text cli-opts))]
    (if (:error/code match)
      (error-result match)
      (let [today (.toLocalDate ((:clock components)))
            tasks (controllers.task/search match components)
            rows  (mapv #(adapters.task/task->display-row % today {:parent-ref (:parent-id %)}) tasks)]
        {:exit 0 :out (framed :search {} (when (seq rows) (render/flat-lines rows opts))) :err ""}))))

(defn- remote-set [components {:keys [opts]}]
  (let [result (controllers.sync/remote-set! (:url opts) components)]
    (if (:error/code result)
      (error-result result)
      {:exit 0 :out (str (messages/say :remote/set {}) "\n") :err ""})))

(defn- push [components {:keys [opts]}]
  (let [result (controllers.sync/push! (boolean (:force opts)) components)]
    (if (:error/code result)
      (error-result result)
      {:exit 0 :out (str (messages/say (keyword "sync" (name (:outcome result))) {}) "\n") :err ""})))

(defn- pull [components {:keys [opts]}]
  (let [{:keys [outcome local-changed?] :as result} (controllers.sync/pull! (boolean (:force opts)) components)]
    (cond
      (:error/code result) (error-result result)
      local-changed?       {:exit 0 :out (str (messages/say :sync/pulled-local-changed {}) "\n") :err ""}
      :else                {:exit 0 :out (str (messages/say (if (= :nothing-new outcome) :sync/in-sync (keyword "sync" (name outcome))) {}) "\n") :err ""})))

(defn- update-jar [components _]
  (let [{:keys [outcome from to version] :as result} (controllers.update/update! components)]
    (cond
      (:error/code result)  (error-result result)
      (= :updated outcome)  {:exit 0
                             :out  (str (messages/say :update/updated {:from from :to to}) "\n")
                             :err  ""}
      :else                 {:exit 0 :out (str (messages/say :update/current {:version version}) "\n") :err ""})))

(defn- fallback [{:keys [opts]}]
  (if (:help opts)
    {:exit 0 :out (str (messages/say :cli/usage {}) "\n") :err ""}
    {:exit 1 :out "" :err (str (messages/say :cli/usage {}) "\n")}))

(defn- version [components {:keys [opts] :as parsed}]
  (if (:version opts)
    {:exit 0
     :out  (str (messages/say :cli/version
                              {:version (cond
                                          (nil? ((get-in components [:jar :running]))) "dev"
                                          :else (or ((get-in components [:jar :version])) "(unversioned)"))})
                "\n")
     :err  ""}
    (fallback parsed)))

(defn- transition [components command {:keys [opts] :as parsed}]
  (if-let [id-arg (:id opts)]
    (let [id (adapters.task/parse-id id-arg)]
      (if (:error/code id)
        (error-result id)
        (let [{:keys [outcome task] :as result} (controllers.task/transition! id command components)]
          (if (:error/code result)
            (error-result result)
            {:exit 0 :out (str (messages/say (keyword "transition" (str (name command) "-" (name outcome))) {:id (:id task)}) "\n") :err ""}))))
    (fallback parsed)))

(defn- edit [components {:keys [opts] :as parsed}]
  (if-let [id-arg (:id opts)]
    (let [id      (adapters.task/parse-id id-arg)
          today   (.toLocalDate ((:clock components)))
          changes (when-not (:error/code id) (adapters.task/args->changes opts today))]
      (cond
        (:error/code id)      (error-result id)
        (:error/code changes) (error-result changes)
        :else
        (let [task (controllers.task/edit! id changes components)]
          (if (:error/code task)
            (error-result task)
            {:exit 0 :out (str (messages/say :task/edited {:id (:id task)}) "\n") :err ""}))))
    (fallback parsed)))

(defn- rm [components {:keys [opts] :as parsed}]
  (if-let [id-arg (:id opts)]
    (let [id (adapters.task/parse-id id-arg)]
      (if (:error/code id)
        (error-result id)
        (let [{:keys [subtasks] :as result} (controllers.task/remove! id (boolean (:force opts)) components)]
          (if (:error/code result)
            (error-result result)
            {:exit 0
             :out  (str (messages/say :task/removed {:id id :subtasks subtasks}) "\n")
             :err  ""}))))
    (fallback parsed)))

(defn- view [components opts {cli-opts :opts :as parsed}]
  (if-let [id-arg (:id cli-opts)]
    (let [id (adapters.task/parse-id id-arg)]
      (if (:error/code id)
        (error-result id)
        (let [result (controllers.task/view-task id components)]
          (if (:error/code result)
            (error-result result)
            (let [today (.toLocalDate ((:clock components)))
                  lines (render/detail-lines (adapters.task/task->detail result today) opts)]
              {:exit 0 :out (framed :view {} lines) :err ""})))))
    (fallback parsed)))

(defn- option-word
  "The option as typed, dashes kept and any `=value` dropped, or nil when the
  word is not an option. A dash before a digit, as in `-1d`, starts a value."
  [word]
  (when (re-find #"^--?[A-Za-z]" word)
    (first (str/split word #"=" 2))))

(defn- shape-checked
  "Refuse an option the entry does not define, then a word beyond its
  positional arguments, before the handler sees any value. Options are read
  from the raw words: babashka.cli stores `--title x` under the same key as a
  positional title, and stops reading options at the first extra word."
  [argv {:keys [cmds coerce args->opts] handler :fn :as entry}]
  (let [allowed (apply disj (set (keys coerce)) args->opts)]
    (assoc entry :fn
           (fn [{:keys [opts args] :as parsed}]
             (if-let [option (or (some #(when-let [w (option-word %)]
                                          (when-not (allowed (keyword (str/replace w #"^--?" ""))) w))
                                       (drop (count cmds) argv))
                                 (some #(when-not (contains? coerce %) (str "--" (name %))) (keys opts)))]
               (error-result {:error/code    :validation/unknown-option
                              :error/details {:option option}})
               (if (seq args)
                 (error-result {:error/code    :validation/extra-argument
                                :error/details {:input (first args)}})
                 (handler parsed)))))))

(defn- empty-search-first
  "babashka.cli reads a punctuation-only word such as `-.` as an option, so no
  text arrives. Answer that as an empty search before the shape check calls
  the option unknown."
  [{handler :fn :as entry}]
  (assoc entry :fn
         (fn [{:keys [opts args] :as parsed}]
           (let [match (adapters.task/search-match (:text opts))]
             (if (and (empty? args)
                      (:error/code match)
                      (not-any? #(re-find #"[A-Za-z]" (name %)) (keys (dissoc opts :text))))
               (error-result match)
               (handler parsed))))))

(defn- dispatch [components opts argv]
  (cli/dispatch (conj (into (mapv (partial shape-checked argv)
                            [{:cmds       ["tasks" "add"]
                              :fn         (partial add components)
                              :args->opts [:title]
                              :coerce     {:title :string :due :string :note :string :parent :string}}
                             {:cmds   ["tasks" "list"]
                              :fn     (partial list-tasks components opts)
                              :coerce {:all :boolean :page :string}}
                             {:cmds       ["tasks" "view"]
                              :fn         (partial view components opts)
                              :args->opts [:id]
                              :coerce     {:id :string}}
                             {:cmds       ["tasks" "start"]
                              :fn         (partial transition components :start)
                              :args->opts [:id]
                              :coerce     {:id :string}}
                             {:cmds       ["tasks" "done"]
                              :fn         (partial transition components :done)
                              :args->opts [:id]
                              :coerce     {:id :string}}
                             {:cmds       ["tasks" "drop"]
                              :fn         (partial transition components :drop)
                              :args->opts [:id]
                              :coerce     {:id :string}}
                             {:cmds       ["tasks" "reopen"]
                              :fn         (partial transition components :reopen)
                              :args->opts [:id]
                              :coerce     {:id :string}}
                             {:cmds       ["tasks" "edit"]
                              :fn         (partial edit components)
                              :args->opts [:id]
                              :coerce     {:id :string :title :string :due :string :note :string :clear-due :boolean}}
                             {:cmds       ["tasks" "rm"]
                              :fn         (partial rm components)
                              :args->opts [:id]
                              :coerce     {:id :string :force :boolean}}
                             {:cmds       ["tasks" "day"]
                              :fn         (partial day components opts)
                              :args->opts [:date]
                              :coerce     {:date :string}}
                             {:cmds       ["remote" "set"]
                              :fn         (partial remote-set components)
                              :args->opts [:url]
                              :coerce     {:url :string}}
                             {:cmds   ["push"]
                              :fn     (partial push components)
                              :coerce {:force :boolean}}
                             {:cmds   ["pull"]
                              :fn     (partial pull components)
                              :coerce {:force :boolean}}
                             {:cmds   ["update"]
                              :fn     (partial update-jar components)
                              :coerce {}}])
                          [(empty-search-first
                            (shape-checked argv {:cmds       ["tasks" "search"]
                                                 :fn         (partial search components opts)
                                                 :args->opts [:text]
                                                 :coerce     {:text :string}}))])
                      {:cmds [] :fn (partial version components)})
                argv))

(defn- missing-value
  "babashka.cli throws when a value option ends the argv; turn that into our
  error and rethrow anything else."
  [e]
  (let [{:keys [type cause implicit-value option msg]} (ex-data e)]
    (if (and (= :org.babashka/cli type) (= :coerce cause)
             (or implicit-value (some-> msg (str/includes? "(implicit)"))))
      (error-result {:error/code    :validation/missing-value
                     :error/details {:option (name option)}})
      (throw e))))

(defn run
  [components opts argv]
  (try
    (dispatch components opts (cond-> argv (= "t" (first argv)) (assoc 0 "tasks")))
    (catch clojure.lang.ExceptionInfo e
      (let [{:keys [type cause] :as data} (ex-data e)]
        (if (and (= :org.babashka/cli type) (#{:input-exhausted :no-match} cause))
          (fallback data)
          (missing-value e))))))

(defn color?
  "Color is on for a terminal unless NO_COLOR is set, even to an empty value."
  [env tty?]
  (and tty? (nil? (get env "NO_COLOR"))))

(defn -main
  [& args]
  (let [env                    (into {} (System/getenv))
        opts                   {:color? (color? env (terminal/tty? :stdout))}
        {:keys [exit out err]} (run (components/for-args env (vec args)) opts (vec args))]
    (print out)
    (flush)
    (binding [*out* *err*]
      (print err)
      (flush))
    (System/exit exit)))
