(ns knope.controllers.update
  (:require [knope.logic.update :as logic.update]))

(defn- failed [result]
  (when (:error/code result) result))

(defn- install!
  "Any failure after the temp file exists discards it."
  [{:keys [release jar]} jar-path tag]
  (let [temp ((:temp-beside! jar) jar-path)]
    (or (failed temp)
        (when-let [error (or (failed ((:download! release) tag temp))
                             (failed ((:replace! jar) temp jar-path)))]
          ((:discard! jar) temp)
          error))))

(defn update!
  [{:keys [release jar] :as components}]
  (if-let [{:keys [path]} ((:running jar))]
    (let [latest ((:latest-tag release))
          tag    (:ok latest)]
      (or (failed latest)
          (if (nil? (logic.update/parse-version tag))
            {:error/code :update/no-release}
            (let [installed ((:version jar))]
              (if (= :up-to-date (logic.update/action installed tag))
                {:outcome :up-to-date :version installed}
                (or (install! components path tag)
                    {:outcome :updated :from installed :to (logic.update/bare tag)}))))))
    {:error/code :update/not-a-jar}))
