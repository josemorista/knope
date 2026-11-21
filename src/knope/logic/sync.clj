(ns knope.logic.sync)

(defn push-action
  "For a machine that never synced, the remote has moved on when it already holds a knope file."
  [{:keys [journal? remote-commit synced-commit fingerprint synced-fingerprint remote-has-file? force?]}]
  (cond
    journal?                          :journal-present
    (and (= remote-commit synced-commit)
         (= fingerprint synced-fingerprint)) :in-sync
    force?                            :push
    (if synced-commit
      (not= remote-commit synced-commit)
      remote-has-file?)               :remote-changed
    :else                             :push))

(defn pull-action
  [{:keys [journal? remote-commit synced-commit fingerprint synced-fingerprint
           remote-has-file? local-has-tasks? force?]}]
  (cond
    journal?                          :journal-present
    (not remote-has-file?)            :nothing-to-pull
    (= remote-commit synced-commit)   :nothing-new
    force?                            :pull
    (and (nil? synced-commit) local-has-tasks?) :first-pull-over-tasks
    (and synced-commit (not= fingerprint synced-fingerprint)) :local-changed
    :else                             :pull))
