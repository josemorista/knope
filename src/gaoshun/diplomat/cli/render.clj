(ns gaoshun.diplomat.cli.render
  (:require [clojure.string :as str]))

(def ^:private urgency-colors {:overdue "\u001b[31m" :soon "\u001b[33m" :later "\u001b[2m"})

(def ^:private reset "\u001b[0m")

(defn- colorize [text urgency color?]
  (if color?
    (str (urgency-colors urgency) text reset)
    text))

(defn- row-line [{:keys [glyph id title progress due-label urgency dimmed?]} indent color?]
  (let [line (cond-> (str indent glyph " " id " " title)
               progress (str "  " progress)
               due-label (str "  " (colorize due-label urgency color?)))]
    (if (and dimmed? color?)
      (str (:later urgency-colors) line reset)
      line)))

(defn tree-lines
  "Entries are {:row root-row :children [child-row ...]}. Subtasks are indented
  two spaces."
  [entries {:keys [color?]}]
  (if (empty? entries)
    ["Nothing pending."]
    (into []
          (mapcat (fn [{:keys [row children]}]
                    (cons (row-line row "" color?)
                          (map #(row-line % "  " color?) children))))
          entries)))
