(ns gaoshun.logic.date
  (:import (java.time LocalDate)
           (java.time.format DateTimeParseException)))

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
        (let [days (Long/parseLong n)]
          (if (= "+" sign) (.plusDays today days) (.minusDays today days)))
        (parse-iso s)))))
