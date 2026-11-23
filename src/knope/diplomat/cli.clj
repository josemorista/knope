(ns knope.diplomat.cli
  (:require [babashka.cli :as cli]
            [babashka.terminal :as terminal]
            [clojure.string :as str]
            [knope.adapters.task :as adapters.task]
            [knope.components :as components]
            [knope.controllers.sync :as controllers.sync]
            [knope.controllers.task :as controllers.task]
            [knope.controllers.update :as controllers.update]
            [knope.diplomat.cli.render :as render]))

(def ^:private usage
  "Usage: knope <command> [options]

Commands:
  add <title> [--due DATE] [--note TEXT] [--parent ID]          Add a task, or a subtask of ID.
  list [--all] [--page N]                                       Show pending tasks, 50 per page; --all includes closed ones.
  view ID                                                       Show one task in full: details, note, parent and subtasks.
  start ID                                                      Mark a task as in progress.
  done ID                                                       Mark a task as done.
  drop ID                                                       Mark a task as dropped.
  reopen ID                                                     Move a done or dropped task back to todo.
  edit ID [--title T] [--due DATE] [--note TEXT] [--clear-due]  Change a task's title, due date or note.
  rm ID [--force]                                               Delete a task; --force also deletes its subtasks.
  day [DATE]                                                    Show what was done, dropped and open on a day; today by default.
  search TEXT                                                   Find tasks whose title or note has every word as a prefix.
  remote set <git-url>                                          Sync this database through a git remote, such as a gist.
  push [--force]                                                Upload this database, encrypted; --force replaces newer remote changes.
  pull [--force]                                                Replace this database with the remote's; --force discards local changes.
  update                                                        Replace this knope.jar with the latest release.
  --version                                                     Show the installed version.
")

(defn- error-message [{:error/keys [code details]}]
  (case code
    :task/not-found           (str "Task " (:id details) " not found.")
    :task/nesting-too-deep    (str "Task " (:id details) " is a subtask; subtasks can't have subtasks.")
    :task/has-subtasks        (let [n (:count details)]
                                (str "Task " (:id details) " has " n (if (= 1 n) " subtask" " subtasks")
                                     ". Use --force to delete " (if (= 1 n) "it" "them") " too."))
    :task/illegal-transition  (str "Task " (:id details) " is " (name (:status details)) "; reopen it first.")
    :validation/bad-id        (str "'" (:input details) "' is not a task id.")
    :validation/missing-title "A task needs a title."
    :validation/nothing-to-edit "Nothing to change. Pass --title, --due, --note or --clear-due."
    :validation/conflicting-flags "--due and --clear-due can't be used together."
    :validation/bad-date      (str "Can't read date '" (:input details)
                                   "'. Use YYYY-MM-DD, today, tomorrow, yesterday, +Nd or -Nd.")
    :validation/empty-search  "Search needs at least one word."
    :validation/bad-page      "--page needs a number."
    :validation/missing-value (str "--" (:option details) " needs a value.")
    :validation/unknown-option (str "Unknown option '" (:option details) "'.")
    :validation/extra-argument (str "Unexpected argument '" (:input details)
                                    "'. Put text with spaces in quotes.")
    :validation/bad-remote    "remote set needs a git URL, for example: knope remote set git@gist.github.com:<id>.git"
    :sync/no-remote           "No remote is set for this database. Run knope remote set <git-url> first."
    :sync/journal-present     (str "A SQLite journal sits at " (:path details)
                                   ". Another knope command may be writing right now, so try again in a moment."
                                   " If no knope command is running, that file holds nothing to recover and you can delete it.")
    :sync/remote-changed      "The remote changed since this machine last synced. Run knope pull first, or knope push --force to add this database on top."
    :sync/local-changed       "This database has local changes that would be lost. Run knope push first, or knope pull --force to take the remote's copy."
    :sync/first-pull-over-tasks "This database already has tasks, and they would be lost. Run knope push --force to keep them, or knope pull --force to take the remote's copy."
    :sync/nothing-to-pull     "There is nothing to pull: the remote holds no tasks.knope yet. Run knope push to upload this database."
    :sync/no-terminal         "Sync needs a terminal to read the passphrase. Nothing was changed. Run the command again from a terminal."
    :sync/wrong-passphrase    "Wrong passphrase, or the remote file is corrupted. Nothing was changed. Run the command again with the right passphrase."
    :sync/passphrase-mismatch "The passphrases don't match. Nothing was pushed. Run knope push again to retry."
    :sync/remote-file-link    "The remote's tasks.knope is a symbolic link, and knope won't follow it. Nothing was changed. Remove it from the remote, then run knope push."
    :sync/git-failed          (str (:err details)
                                   "\nCheck that git can reach the remote: an SSH key, or for HTTPS, gh auth setup-git.")
    :sync/git-missing         "Sync needs git installed and on your PATH."
    :update/not-a-jar         "update only works on an installed knope.jar. This is a checkout, so use git pull."
    :update/unreachable       "Could not reach GitHub. Check your network and try again."
    :update/no-release        "Could not find the latest release on GitHub. It may be rate limited, so try again later."
    :update/not-writable      (str "Can't write next to " (:path details) ". Check the folder's permissions.")
    :update/download-failed   "The download failed, so the installed jar was left as it was."))

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
          {:exit 0 :out (str "Task " (:id task) " is on the list! I've already started a binder for it.\n") :err ""})))))

(defn- more-line [all? page]
  (str "More on page " (inc page) ": knope list " (when all? "--all ") "--page " (inc page)))

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

(defn- day [components opts {cli-opts :opts}]
  (let [today (.toLocalDate ((:clock components)))
        date  (adapters.task/args->day cli-opts today)]
    (if (:error/code date)
      (error-result date)
      (let [sections (controllers.task/day date components)
            row      (fn [task]
                       (-> (adapters.task/task->display-row task today {:parent-ref (:parent-id task)})
                           (dissoc :due-label :urgency)))
            ;; A task finished after the day was still open on it.
            as-open  (fn [task]
                       (cond-> task (#{:done :dropped} (:status task)) (assoc :status :todo)))
            lines    (render/section-lines date
                                           (-> sections
                                               (update-vals #(mapv row %))
                                               (assoc :open (mapv (comp row as-open) (:open sections))))
                                           opts)]
        {:exit 0 :out (str (str/join "\n" lines) "\n") :err ""}))))

(defn- search [components opts {cli-opts :opts}]
  (let [match (adapters.task/search-match (:text cli-opts))]
    (if (:error/code match)
      (error-result match)
      (let [today (.toLocalDate ((:clock components)))
            tasks (controllers.task/search match components)
            rows  (mapv #(adapters.task/task->display-row % today {:parent-ref (:parent-id %)}) tasks)]
        {:exit 0 :out (str (str/join "\n" (render/flat-lines rows opts)) "\n") :err ""}))))

(defn- remote-set [components {:keys [opts]}]
  (let [result (controllers.sync/remote-set! (:url opts) components)]
    (if (:error/code result)
      (error-result result)
      {:exit 0 :out "Remote set! Every great partnership starts with a handshake.\n" :err ""})))

(def ^:private sync-messages
  {:pushed  "Your tasks are safely in the remote, encrypted and accounted for. Ann would be so proud."
   :pulled  "Your tasks are here! Fresh from the remote, like a waffle from JJ's."
   :in-sync "Everything is already in sync. Nothing to do but celebrate."})

(defn- push [components {:keys [opts]}]
  (let [result (controllers.sync/push! (boolean (:force opts)) components)]
    (if (:error/code result)
      (error-result result)
      {:exit 0 :out (str (sync-messages (:outcome result)) "\n") :err ""})))

(defn- pull [components {:keys [opts]}]
  (let [{:keys [outcome local-changed?] :as result} (controllers.sync/pull! (boolean (:force opts)) components)]
    (cond
      (:error/code result) (error-result result)
      local-changed?       {:exit 0 :out "Nothing new on the remote, so we're in sync with your last pull. But you've been busy! Share your changes with knope push.\n" :err ""}
      :else                {:exit 0 :out (str (sync-messages (if (= :nothing-new outcome) :in-sync outcome)) "\n") :err ""})))

(defn- update-jar [components _]
  (let [{:keys [outcome from to version] :as result} (controllers.update/update! components)]
    (cond
      (:error/code result)  (error-result result)
      (= :updated outcome)  {:exit 0
                             :out  (str "Updated knope from " (or from "an unversioned release") " to " to
                                        ". Progress, and I have a binder for it.\n")
                             :err  ""}
      :else                 {:exit 0 :out (str "Already on " version ". Nothing to do but admire it.\n") :err ""})))

(defn- fallback [{:keys [opts]}]
  (if (:help opts)
    {:exit 0 :out usage :err ""}
    {:exit 1 :out "" :err usage}))

(defn- version [components {:keys [opts] :as parsed}]
  (if (:version opts)
    {:exit 0
     :out  (str "knope "
                (cond
                  (nil? ((get-in components [:jar :running]))) "dev"
                  :else (or ((get-in components [:jar :version])) "(unversioned)"))
                "\n")
     :err  ""}
    (fallback parsed)))

(def ^:private transition-messages
  {:start  {:applied   "Task %s is in progress. Let's make Pawnee proud!"
            :unchanged "Task %s is already in progress. I love the enthusiasm."}
   :done   {:applied   "Task %s is DONE! This calls for waffles."
            :unchanged "Task %s is already done. You can't finish it twice, but I admire the spirit."}
   :drop   {:applied   "Task %s is dropped. Knowing your priorities is strong leadership."
            :unchanged "Task %s is already dropped."}
   :reopen {:applied   "Task %s is back on the list. Everyone deserves a second chance."
            :unchanged "Task %s is already open and waiting for you."}})

(defn- transition [components command {:keys [opts] :as parsed}]
  (if-let [id-arg (:id opts)]
    (let [id (adapters.task/parse-id id-arg)]
      (if (:error/code id)
        (error-result id)
        (let [{:keys [outcome task] :as result} (controllers.task/transition! id command components)]
          (if (:error/code result)
            (error-result result)
            {:exit 0 :out (str (format (get-in transition-messages [command outcome]) (:id task)) "\n") :err ""}))))
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
            {:exit 0 :out (str "Task " (:id task) " is updated. Revisions make a plan stronger.\n") :err ""}))))
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
             :out  (str (cond
                          (zero? subtasks) (str "Task " id " is gone.")
                          (= 1 subtasks)   (str "Task " id " and its subtask are gone.")
                          :else            (str "Task " id " and its " subtasks " subtasks are gone."))
                        " A good public servant knows when to let go.\n")
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
              {:exit 0 :out (str (str/join "\n" lines) "\n") :err ""})))))
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
                            [{:cmds       ["add"]
                              :fn         (partial add components)
                              :args->opts [:title]
                              :coerce     {:title :string :due :string :note :string :parent :string}}
                             {:cmds   ["list"]
                              :fn     (partial list-tasks components opts)
                              :coerce {:all :boolean :page :string}}
                             {:cmds       ["view"]
                              :fn         (partial view components opts)
                              :args->opts [:id]
                              :coerce     {:id :string}}
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
                              :coerce     {:id :string}}
                             {:cmds       ["edit"]
                              :fn         (partial edit components)
                              :args->opts [:id]
                              :coerce     {:id :string :title :string :due :string :note :string :clear-due :boolean}}
                             {:cmds       ["rm"]
                              :fn         (partial rm components)
                              :args->opts [:id]
                              :coerce     {:id :string :force :boolean}}
                             {:cmds       ["day"]
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
                            (shape-checked argv {:cmds       ["search"]
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
    (dispatch components opts argv)
    (catch clojure.lang.ExceptionInfo e
      (missing-value e))))

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
