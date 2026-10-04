(ns gaoshun.diplomat.cli.render
  (:require [clojure.string :as str]))

(def ^:private urgency-colors {:overdue "\u001b[31m" :soon "\u001b[33m" :later "\u001b[2m"})

(def ^:private reset "\u001b[0m")

(defn- colorize [text urgency color?]
  (if color?
    (str (urgency-colors urgency) text reset)
    text))

(defn- row-line [{:keys [glyph id title due-label urgency]} color?]
  (cond-> (str glyph " " id " " title)
    due-label (str "  " (colorize due-label urgency color?))))

(defn tree-lines
  [rows {:keys [color?]}]
  (if (empty? rows)
    ["Nothing pending."]
    (mapv #(row-line % color?) rows)))
