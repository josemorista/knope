(ns knope.diplomat.cli.messages)

(defn- with-id
  "A text that fills the task id into a format string."
  [fmt]
  (fn [{:keys [id]}] (format fmt id)))

(defn- ids
  "Three variants that each fill the task id."
  [& fmts]
  {:variants (mapv with-id fmts)})

(defn- plain [& texts]
  {:variants (vec texts)})

(defn- removed-lead [{:keys [id subtasks]}]
  (cond
    (zero? subtasks) (str "Task " id " is gone.")
    (= 1 subtasks)   (str "Task " id " and its subtask are gone.")
    :else            (str "Task " id " and its " subtasks " subtasks are gone.")))

(defn- removed [closing]
  (fn [details] (str (removed-lead details) " " closing)))

(defn- error
  "An error entry: its instruction is the only variant, the Leslie line follows it."
  [instruction leslie]
  {:variants [instruction] :leslie leslie})

(defn- on-date [fmt]
  (fn [{:keys [date]}] (format fmt date)))

(defn- updated [fmt]
  (fn [{:keys [from to]}] (format fmt (or from "an unversioned release") to)))

(defn- current [fmt]
  (fn [{:keys [version]}] (format fmt version)))

(def entries
  {:cli/usage
   (plain "Usage: knope <command> [options]

Commands:
  tasks add <title> [--due DATE] [--note TEXT] [--parent ID]          Add a task, or a subtask of ID.
  tasks list [--all] [--page N]                                       Show pending tasks, 50 per page; --all includes closed ones.
  tasks view ID                                                       Show one task in full: details, note, parent and subtasks.
  tasks start ID                                                      Mark a task as in progress.
  tasks done ID                                                       Mark a task as done.
  tasks drop ID                                                       Mark a task as dropped.
  tasks reopen ID                                                     Move a done or dropped task back to todo.
  tasks edit ID [--title T] [--due DATE] [--note TEXT] [--clear-due]  Change a task's title, due date or note.
  tasks rm ID [--force]                                               Delete a task; --force also deletes its subtasks.
  tasks day [DATE]                                                    Show what was done, dropped and open on a day; today by default.
  tasks search TEXT                                                   Find tasks whose title or note has every word as a prefix.
  remote set <git-url>                                                Sync this database through a git remote, such as a gist.
  push [--force]                                                      Upload this database, encrypted; --force replaces newer remote changes.
  pull [--force]                                                      Replace this database with the remote's; --force discards local changes.
  update                                                              Replace this knope.jar with the latest release.
  --version                                                           Show the installed version.")

   :cli/version
   (plain (fn [{:keys [version]}] (str "knope " version)))

   :list/more
   (plain (fn [{:keys [all? page]}]
            (str "More on page " (inc page) ": knope tasks list " (when all? "--all ") "--page " (inc page))))

   :task/added
   (ids "Task %s is on the list! I've already started a binder for it."
        "Task %s is on the list! I color-coded it before you finished typing."
        "Task %s is officially on the agenda. Pawnee thanks you for your service.")

   :task/edited
   (ids "Task %s is updated. Revisions make a plan stronger."
        "Task %s is updated. Every great plan has a second draft."
        "Task %s is updated. I've already reprinted the binder.")

   :task/removed
   {:variants [(removed "A good public servant knows when to let go.")
               (removed "I'll keep a memorial page in my binder.")
               (removed "Clearing the agenda is a form of leadership.")]}

   :transition/start-applied
   (ids "Task %s is in progress. Let's make Pawnee proud!"
        "Task %s is in progress. Grab a binder, we're doing this."
        "Task %s is underway! I can feel the momentum from here.")

   :transition/start-unchanged
   (ids "Task %s is already in progress. I love the enthusiasm."
        "Task %s is already moving. Starting it twice is very on brand for us."
        "Task %s was already in progress. Your dedication is noted in my files.")

   :transition/done-applied
   (ids "Task %s is DONE! This calls for waffles."
        "Task %s is DONE! I'm making a commemorative plaque."
        "Task %s is DONE! Somebody cue the celebration playlist.")

   :transition/done-unchanged
   (ids "Task %s is already done. You can't finish it twice, but I admire the spirit."
        "Task %s was already done. Victory laps are allowed, though."
        "Task %s is already done. I'm noting your thoroughness in my binder.")

   :transition/drop-applied
   (ids "Task %s is dropped. Knowing your priorities is strong leadership."
        "Task %s is dropped. Sometimes a good leader says no."
        "Task %s is dropped. We tabled it, and that is a valid parliamentary move.")

   :transition/drop-unchanged
   (ids "Task %s is already dropped."
        "Task %s was already dropped. Its file is closed and archived."
        "Task %s is already dropped. Let it rest in the archives.")

   :transition/reopen-applied
   (ids "Task %s is back on the list. Everyone deserves a second chance."
        "Task %s is back on the list. Comebacks are my favorite genre."
        "Task %s is back! I kept its binder warm.")

   :transition/reopen-unchanged
   (ids "Task %s is already open and waiting for you."
        "Task %s is already open. It never left, it believed in you."
        "Task %s is still open and ready when you are.")

   :remote/set
   (plain "Remote set! Every great partnership starts with a handshake."
          "Remote set! I've added it to my emergency contacts binder."
          "Remote set! This is the beginning of a beautiful intergovernmental relationship.")

   :sync/pushed
   (plain "Your tasks are safely in the remote, encrypted and accounted for. Ann would be so proud."
          "Pushed and encrypted. Your tasks are safer than the town time capsule."
          "Your tasks are in the remote, locked up tight. That is responsible governance.")

   :sync/pulled
   (plain "Your tasks are here! Fresh from the remote, like a waffle from JJ's."
          "Pulled! Every task is home, and I counted them twice."
          "Your tasks made it back safely. Welcome home, everybody.")

   :sync/in-sync
   (plain "Everything is already in sync. Nothing to do but celebrate."
          "Already in sync. Perfectly aligned, like a well-run committee."
          "Everything matches. I checked, then I made a chart about checking.")

   :sync/pulled-local-changed
   (plain "Nothing new on the remote, so we're in sync with your last pull. But you've been busy! Share your changes with knope push."
          "Nothing new on the remote since your last pull, but you have local changes. Share them with knope push."
          "The remote has nothing new, and you have changes it hasn't seen. Run knope push so everyone is in the loop.")

   :update/updated
   {:variants [(updated "Updated knope from %s to %s. Progress, and I have a binder for it.")
               (updated "Updated knope from %s to %s. A fresh start, like the first day of a new term.")
               (updated "Updated knope from %s to %s. Infrastructure improvements are my love language.")]}

   :update/current
   {:variants [(current "Already on %s. Nothing to do but admire it.")
               (current "Already on %s. You're as current as tomorrow's agenda.")
               (current "Already on %s. Nothing to update, so let's go get waffles.")]}

   :list/empty
   (plain "Nothing pending. Go get some waffles, you've earned them."
          "Nothing pending. Your agenda is cleaner than a freshly mowed park."
          "Nothing pending! Take a breath, then start a new binder.")

   :search/empty
   (plain "No matches. I checked every binder twice."
          "No matches. I even checked the archives in the basement."
          "No matches. I searched harder than a parks department audit.")

   :day/empty
   {:variants [(on-date "Nothing recorded for %s. Even public servants need a day off.")
               (on-date "Nothing recorded for %s. A quiet day in Pawnee.")
               (on-date "Nothing recorded for %s. Rest is part of the plan too.")]}

   :list/intro
   (plain "Here's the agenda. Let's get to work!"
          "Your tasks, organized and ready for action."
          "Behold, the master plan.")

   :list/ending
   (plain "You've got this. I believe in you more than I believe in waffles."
          "One task at a time. That's how parks get built."
          "Now go make Pawnee proud.")

   :search/intro
   (plain "I searched every binder. Here's what turned up."
          "Search results, fresh from the archives."
          "Found them! Here's what matches.")

   :search/ending
   (plain "That's everything I could find."
          "If it's not here, it's not in any binder."
          "Investigation complete.")

   :day/intro
   (plain "Here's how the day went."
          "The official record for the day."
          "Minutes of the day, as taken by yours truly.")

   :day/ending
   (plain "Every day is a chance to do better than the last."
          "Recorded for posterity."
          "Another day in the books.")

   :view/intro
   (plain "Here's the full file."
          "Everything I know about this task."
          "Pulling the binder for you.")

   :view/ending
   (plain "That's the whole story."
          "File closed. For now."
          "Let's go get it done.")

   :task/not-found
   (error (fn [{:keys [id]}] (str "Task " id " not found."))
          "I looked everywhere, even in the Parks basement.")

   :task/nesting-too-deep
   (error (fn [{:keys [id]}] (str "Task " id " is a subtask; subtasks can't have subtasks."))
          "Even a good bureaucracy has limits on subcommittees.")

   :task/has-subtasks
   (error (fn [{:keys [id count]}]
            (str "Task " id " has " count (if (= 1 count) " subtask" " subtasks")
                 ". Use --force to delete " (if (= 1 count) "it" "them") " too."))
          "I can't just abandon its little ones.")

   :task/illegal-transition
   (error (fn [{:keys [id status]}] (str "Task " id " is " (name status) "; reopen it first."))
          "There's a procedure for this, and procedures matter.")

   :validation/bad-id
   (error (fn [{:keys [input]}] (str "'" input "' is not a task id."))
          "Task ids are numbers. I'd know, I filed every one.")

   :validation/missing-title
   (error "A task needs a title."
          "Every great project starts with a name.")

   :validation/nothing-to-edit
   (error "Nothing to change. Pass --title, --due, --note or --clear-due."
          "I'm ready to revise, I just need to know what.")

   :validation/conflicting-flags
   (error "--due and --clear-due can't be used together."
          "I can't set a date and cancel it in the same breath.")

   :validation/bad-date
   (error (fn [{:keys [input]}]
            (str "Can't read date '" input
                 "'. Use YYYY-MM-DD, today, tomorrow, yesterday, +Nd or -Nd."))
          "I love calendars, but I can't read that one.")

   :validation/empty-search
   (error "Search needs at least one word."
          "Give me something to look for, and I won't rest until I find it.")

   :validation/bad-page
   (error "--page needs a number."
          "Pages are numbered. That's the whole point of pages.")

   :validation/missing-value
   (error (fn [{:keys [option]}] (str "--" option " needs a value."))
          "An option without a value is like a park without benches.")

   :validation/unknown-option
   (error (fn [{:keys [option]}] (str "Unknown option '" option "'."))
          "That's not in any of my binders.")

   :validation/extra-argument
   (error (fn [{:keys [input]}] (str "Unexpected argument '" input "'. Put text with spaces in quotes."))
          "I want to hear every word, but they need to come in quotes.")

   :validation/bad-remote
   (error "remote set needs a git URL, for example: knope remote set git@gist.github.com:<id>.git"
          "A partnership needs an address.")

   :sync/no-remote
   (error "No remote is set for this database. Run knope remote set <git-url> first."
          "We need somewhere to send these before we can send them.")

   :sync/journal-present
   (error (fn [{:keys [path]}]
            (str "A SQLite journal sits at " path
                 ". Another knope command may be writing right now, so try again in a moment."
                 " If no knope command is running, that file holds nothing to recover and you can delete it."))
          "Patience. Good government takes a moment.")

   :sync/remote-changed
   (error "The remote changed since this machine last synced. Run knope pull first, or knope push --force to add this database on top."
          "Someone else has been busy. Let's catch up before we share.")

   :sync/local-changed
   (error "This database has local changes that would be lost. Run knope push first, or knope pull --force to take the remote's copy."
          "Your hard work deserves to be saved first.")

   :sync/first-pull-over-tasks
   (error "This database already has tasks, and they would be lost. Run knope push --force to keep them, or knope pull --force to take the remote's copy."
          "I won't let your tasks vanish without a vote.")

   :sync/nothing-to-pull
   (error "There is nothing to pull: the remote holds no tasks.knope yet. Run knope push to upload this database."
          "The remote is as empty as town hall on a holiday.")

   :sync/no-terminal
   (error "Sync needs a terminal to read the passphrase. Nothing was changed. Run the command again from a terminal."
          "Secrets should be whispered in person.")

   :sync/wrong-passphrase
   (error "Wrong passphrase, or the remote file is corrupted. Nothing was changed. Run the command again with the right passphrase."
          "Security matters. I'd never let just anyone in.")

   :sync/passphrase-mismatch
   (error "The passphrases don't match. Nothing was pushed. Run knope push again to retry."
          "Let's try that again, slowly and with feeling.")

   :sync/remote-file-link
   (error "The remote's tasks.knope is a symbolic link, and knope won't follow it. Nothing was changed. Remove it from the remote, then run knope push."
          "I follow procedure, not symbolic links.")

   :sync/git-failed
   (error (fn [{:keys [err]}]
            (str err "\nCheck that git can reach the remote: an SSH key, or for HTTPS, gh auth setup-git."))
          "Even the best plans hit a snag. We'll get there.")

   :sync/git-missing
   (error "Sync needs git installed and on your PATH."
          "Every team needs the right tools.")

   :update/not-a-jar
   (error "update only works on an installed knope.jar. This is a checkout, so use git pull."
          "Checkouts have their own process, and I respect process.")

   :update/unreachable
   (error "Could not reach GitHub. Check your network and try again."
          "GitHub isn't answering. I'll keep the binder open.")

   :update/no-release
   (error "Could not find the latest release on GitHub. It may be rate limited, so try again later."
          "Sometimes the paperwork isn't ready yet.")

   :update/not-writable
   (error (fn [{:keys [path]}] (str "Can't write next to " path ". Check the folder's permissions."))
          "I need permission first. I always get permission first.")

   :update/download-failed
   (error "The download failed, so the installed jar was left as it was."
          "Nothing broke. Your knope is exactly as you left it.")})

(defn say
  "The text of the message under key: one of its variants picked at random,
  filled with details, then the Leslie line on its own line when the entry has one."
  [key details]
  (let [{:keys [variants leslie]} (or (get entries key)
                                      (throw (ex-info "No message for key" {:key key})))
        text                      (rand-nth variants)
        rendered                  (if (fn? text) (text details) text)]
    (cond-> rendered
      leslie (str "\n" leslie))))
