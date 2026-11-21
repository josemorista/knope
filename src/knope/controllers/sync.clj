(ns knope.controllers.sync
  (:require [clojure.string :as str]
            [knope.adapters.crypto :as crypto]
            [knope.diplomat.git :as git]
            [knope.diplomat.sqlite.task :as sqlite.task]
            [knope.diplomat.sync-state :as sync-state]
            [knope.logic.sync :as logic.sync]))

(def ^:private remote-file "tasks.knope")

(defn- failed [result]
  (when (:error/code result) result))

(defn- journal-error [db-path]
  {:error/code :sync/journal-present :error/details {:path (sync-state/journal-path db-path)}})

(def ^:private no-terminal {:error/code :sync/no-terminal})

(defn remote-set!
  [url {:keys [sqlite]}]
  (if (str/blank? url)
    {:error/code :validation/bad-remote}
    (or (failed (git/ls-remote url))
        (do (when (not= url (:remote (sync-state/read-state sqlite)))
              (sync-state/reset-for-remote! sqlite url))
            {:remote url}))))

(defn- observe
  "Refuses a remote file that is a symbolic link before anything reads or writes through it."
  [db-path force?]
  (let [state (sync-state/read-state db-path)
        clone (sync-state/clone-dir db-path)]
    (cond
      (nil? (:remote state))       {:error/code :sync/no-remote}
      (sync-state/journal? db-path) (journal-error db-path)
      :else
      (or (when-not (sync-state/cloned? db-path)
            (failed (git/clone (:remote state) clone)))
          (failed (git/fetch-and-reset clone))
          (let [head (git/head-commit clone)
                file (sync-state/clone-file db-path remote-file)]
            (or (failed head)
                (when (sync-state/link? file)
                  {:error/code :sync/remote-file-link})
                {:state state
                 :clone clone
                 :file  file
                 :obs   {:journal?           (sync-state/journal? db-path)
                         :remote-commit      (:ok head)
                         :synced-commit      (:commit state)
                         :fingerprint        (sync-state/fingerprint db-path)
                         :synced-fingerprint (:fingerprint state)
                         :remote-has-file?   (sync-state/file? file)
                         :local-has-tasks?   (sqlite.task/any-task? db-path)
                         :force?             force?}}))))))

(defn- push-passphrase
  [read-passphrase file iterations]
  (let [passphrase (read-passphrase "Passphrase: ")]
    (cond
      (nil? passphrase) no-terminal

      (sync-state/file? file)
      (or (failed (crypto/decrypt (sync-state/read-file file) passphrase iterations))
          {:passphrase passphrase})

      :else
      (let [again (read-passphrase "Passphrase again: ")]
        (cond
          (nil? again)              no-terminal
          (not= passphrase again)   {:error/code :sync/passphrase-mismatch}
          :else                     {:passphrase passphrase})))))

(defn- upload!
  [db-path {:keys [state clone file obs]} passphrase iterations]
  (sync-state/write-file! file (crypto/encrypt (sync-state/snapshot-bytes! db-path) passphrase iterations))
  (or (failed (git/commit-file clone remote-file))
      (failed (git/push clone))
      (let [head (git/head-commit clone)]
        (or (failed head)
            (do (sync-state/write-state! db-path (assoc state
                                                        :commit (:ok head)
                                                        :fingerprint (:fingerprint obs)))
                {:outcome :pushed})))))

(defn push!
  [force? {:keys [sqlite read-passphrase kdf-iterations]}]
  (let [observed (observe sqlite force?)]
    (or (failed observed)
        (case (logic.sync/push-action (:obs observed))
          :journal-present (journal-error sqlite)
          :in-sync         {:outcome :in-sync}
          :remote-changed  {:error/code :sync/remote-changed}
          :push            (let [checked (push-passphrase read-passphrase (:file observed) kdf-iterations)]
                             (or (failed checked)
                                 (upload! sqlite observed (:passphrase checked) kdf-iterations)))))))

(defn pull!
  "Without force, a database edited while the passphrase was asked is left alone."
  [force? {:keys [sqlite read-passphrase kdf-iterations]}]
  (let [observed (observe sqlite force?)]
    (or (failed observed)
        (let [{:keys [state file obs]} observed]
          (case (logic.sync/pull-action obs)
            :journal-present       (journal-error sqlite)
            :nothing-to-pull       {:error/code :sync/nothing-to-pull}
            :nothing-new           {:outcome        :nothing-new
                                    :local-changed? (not= (:fingerprint obs) (:synced-fingerprint obs))}
            :local-changed         {:error/code :sync/local-changed}
            :first-pull-over-tasks {:error/code :sync/first-pull-over-tasks}
            :pull
            (let [passphrase (read-passphrase "Passphrase: ")
                  plain      (when passphrase
                               (crypto/decrypt (sync-state/read-file file) passphrase kdf-iterations))]
              (cond
                (nil? passphrase)    no-terminal
                (:error/code plain)  plain

                (and (not force?) (not= (:fingerprint obs) (sync-state/fingerprint sqlite)))
                {:error/code :sync/local-changed}

                :else
                (do (sync-state/replace-database! sqlite plain force?)
                    (sync-state/write-state! sqlite (assoc state
                                                           :commit (:remote-commit obs)
                                                           :fingerprint (sync-state/bytes-fingerprint plain)))
                    {:outcome :pulled}))))))))
