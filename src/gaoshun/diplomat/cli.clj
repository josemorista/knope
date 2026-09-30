(ns gaoshun.diplomat.cli
  (:require [babashka.cli :as cli]
            [gaoshun.adapters.task :as adapters.task]
            [gaoshun.components :as components]
            [gaoshun.controllers.task :as controllers.task]))

(def ^:private usage
  "Usage: gaoshun <command> [options]

Commands:
  add <title> [--due DATE] [--note TEXT]   Add a task.
")

(defn- error-message [{:error/keys [code details]}]
  (case code
    :validation/missing-title "A task needs a title."
    :validation/bad-date      (str "Can't read date '" (:input details)
                                   "'. Use YYYY-MM-DD, today, tomorrow, yesterday, +Nd or -Nd.")))

(defn- error-result [error]
  {:exit 1 :out "" :err (str (error-message error) "\n")})

(defn- add [components {:keys [opts]}]
  (let [today (.toLocalDate ((:clock components)))
        attrs (adapters.task/args->new-attrs opts today)]
    (if (:error/code attrs)
      (error-result attrs)
      (let [task (controllers.task/add! attrs components)]
        {:exit 0 :out (str "Added task " (:id task) ".\n") :err ""}))))

(defn- fallback [{:keys [opts]}]
  (if (:help opts)
    {:exit 0 :out usage :err ""}
    {:exit 1 :out "" :err usage}))

(defn run
  [components _opts argv]
  (cli/dispatch [{:cmds       ["add"]
                  :fn         (partial add components)
                  :args->opts [:title]
                  :coerce     {:title :string :due :string :note :string}}
                 {:cmds [] :fn fallback}]
                argv))

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
