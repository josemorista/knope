(ns knope.diplomat.cli.render
  (:require [clojure.string :as str]))

(def ^:private urgency-colors {:overdue "\u001b[31m" :soon "\u001b[33m" :later "\u001b[2m"})

(def ^:private reset "\u001b[0m")

(defn- colorize [text urgency color?]
  (if color?
    (str (urgency-colors urgency) text reset)
    text))

(defn- row-line [{:keys [glyph id title parent-ref progress due-label urgency dimmed?]} indent color?]
  (let [line (cond-> (str indent glyph " " id " " title)
               parent-ref (str " " parent-ref)
               progress (str "  " progress)
               due-label (str "  " (colorize due-label urgency (and color? (not dimmed?)))))]
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

(defn section-lines
  "The date, then each non-empty section of display rows under its heading,
  rows indented two spaces; or a line saying nothing was recorded."
  [date {:keys [done dropped open]} {:keys [color?]}]
  (let [sections (remove (comp empty? second) [["Done" done] ["Dropped" dropped] ["Open" open]])]
    (if (empty? sections)
      [(str "Nothing recorded for " date ".")]
      (into [(str date)]
            (mapcat (fn [[heading rows]]
                      (cons heading (map #(row-line % "  " color?) rows))))
            sections))))

(defn flat-lines
  "One line per display row, or a line saying nothing matched."
  [rows {:keys [color?]}]
  (if (empty? rows)
    ["No matches."]
    (mapv #(row-line % "" color?) rows)))
