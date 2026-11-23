(ns knope.logic.update)

(defn bare
  "The version without its leading v."
  [s]
  (if (and (string? s) (= \v (first s))) (subs s 1) s))

(defn parse-version
  [s]
  (when (string? s)
    (when-let [[_ major minor patch] (re-matches #"v?(\d+)\.(\d+)\.(\d+)" s)]
      (try
        (mapv #(Long/parseLong %) [major minor patch])
        (catch NumberFormatException _ nil)))))

(defn action
  "An installed version that does not parse counts as older than any release."
  [installed latest]
  (let [installed (parse-version installed)]
    (if (or (nil? installed) (pos? (compare (parse-version latest) installed)))
      :update
      :up-to-date)))
