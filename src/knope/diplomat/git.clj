(ns knope.diplomat.git
  (:require [babashka.process :as process]
            [clojure.string :as str]))

(defn- git
  "HTTPS credential prompts are off so a missing login fails at once instead of waiting on the terminal."
  [& args]
  (try
    (let [{:keys [exit out err]} (apply process/shell
                                        {:out       :string
                                         :err       :string
                                         :continue  true
                                         :extra-env {"GIT_TERMINAL_PROMPT" "0"}}
                                        "git" args)]
      (if (zero? exit)
        {:ok (str/trim out)}
        {:error/code :sync/git-failed :error/details {:err (str/trim err)}}))
    (catch java.io.IOException _
      {:error/code :sync/git-missing})))

(defn ls-remote
  [url]
  (git "ls-remote" url))

(defn clone
  [url dir]
  (git "clone" "--quiet" url (str dir)))

(defn fetch-and-reset
  [dir]
  (let [fetched (git "-C" (str dir) "fetch" "--quiet" "origin")]
    (if (:error/code fetched)
      fetched
      (git "-C" (str dir) "reset" "--hard" "--quiet" "origin/HEAD"))))

(defn head-commit
  [dir]
  (git "-C" (str dir) "rev-parse" "origin/HEAD"))

(defn commit-file
  "A fixed identity keeps personal names off the remote and works without a git
  identity. Signing is off because a signing prompt would stall the sync."
  [dir file]
  (let [added (git "-C" (str dir) "add" "--" file)]
    (if (:error/code added)
      added
      (git "-C" (str dir) "-c" "user.name=knope" "-c" "user.email=knope@localhost"
           "-c" "commit.gpgsign=false" "commit" "--quiet" "-m" "knope push"))))

(defn push
  "Never forced: a rejection means the remote moved on since the fetch."
  [dir]
  (let [result (git "-C" (str dir) "push" "--quiet" "origin" "HEAD")]
    (if (str/includes? (str (get-in result [:error/details :err])) "[rejected]")
      {:error/code :sync/remote-changed}
      result)))
