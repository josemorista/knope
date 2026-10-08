(ns gaoshun.logic.date
  (:import (java.time DateTimeException LocalDate)
           (java.time.format DateTimeParseException)
           (java.time.temporal ChronoUnit)))

(defn- parse-iso [s]
  (when (re-matches #"\d{4}-\d{2}-\d{2}" s)
    (try
      (LocalDate/parse s)
      (catch DateTimeParseException _ nil))))

(defn parse-date-input
  [s today]
  (when (string? s)
    (case s
      "today"     today
      "tomorrow"  (.plusDays today 1)
      "yesterday" (.minusDays today 1)
      (if-let [[_ sign n] (re-matches #"([+-])(\d+)d" s)]
        (try
          (let [days (Long/parseLong n)]
            (if (= "+" sign) (.plusDays today days) (.minusDays today days)))
          (catch NumberFormatException _ nil)
          (catch DateTimeException _ nil))
        (parse-iso s)))))

(defn due-label
  [due today]
  (when due
    (let [days (.between ChronoUnit/DAYS today due)]
      (cond
        (neg? days) {:text (str (- days) "d overdue") :urgency :overdue}
        (zero? days) {:text "today" :urgency :soon}
        (= 1 days) {:text "tomorrow" :urgency :soon}
        (<= days 7) {:text (str "in " days "d") :urgency :later}
        :else {:text (str due) :urgency :later}))))
