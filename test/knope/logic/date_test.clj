(ns knope.logic.date-test
  (:require [clojure.test :refer [deftest is testing]]
            [knope.logic.date :as logic.date])
  (:import (java.time LocalDate)))

(def today (LocalDate/of 2026 9 28))

(deftest parse-date-input-accepts-iso
  (is (= (LocalDate/of 2026 10 1) (logic.date/parse-date-input "2026-10-01" today)))
  (testing "past dates are allowed"
    (is (= (LocalDate/of 2020 1 31) (logic.date/parse-date-input "2020-01-31" today)))))

(deftest parse-date-input-accepts-keywords
  (is (= today (logic.date/parse-date-input "today" today)))
  (is (= (LocalDate/of 2026 9 29) (logic.date/parse-date-input "tomorrow" today)))
  (is (= (LocalDate/of 2026 9 27) (logic.date/parse-date-input "yesterday" today))))

(deftest parse-date-input-accepts-day-offsets
  (is (= (LocalDate/of 2026 10 1) (logic.date/parse-date-input "+3d" today)))
  (is (= (LocalDate/of 2026 9 26) (logic.date/parse-date-input "-2d" today)))
  (is (= (LocalDate/of 2026 10 8) (logic.date/parse-date-input "+10d" today))))

(deftest parse-date-input-zero-offset-is-today
  (is (= today (logic.date/parse-date-input "+0d" today)))
  (is (= today (logic.date/parse-date-input "-0d" today))))

(deftest parse-date-input-rolls-over-months-and-years
  (is (= (LocalDate/of 2026 10 2) (logic.date/parse-date-input "+3d" (LocalDate/of 2026 9 29))))
  (is (= (LocalDate/of 2027 1 2) (logic.date/parse-date-input "+3d" (LocalDate/of 2026 12 30))))
  (is (= (LocalDate/of 2027 1 1) (logic.date/parse-date-input "tomorrow" (LocalDate/of 2026 12 31))))
  (is (= (LocalDate/of 2026 12 30) (logic.date/parse-date-input "-2d" (LocalDate/of 2027 1 1))))
  (is (= (LocalDate/of 2026 2 28) (logic.date/parse-date-input "yesterday" (LocalDate/of 2026 3 1)))))

(deftest parse-date-input-returns-nil-for-garbage
  (doseq [s ["fri" "" "  " "+d" "-d" "3d" "+3" "+3days" "+1.5d" "next week"
             "2026-13-01" "2026-02-30" "2026-9-1" "10/01/2026" nil
             "+99999999999999999999d" "+999999999999d" "-999999999999d"]]
    (testing (pr-str s)
      (is (nil? (logic.date/parse-date-input s today))))))

(deftest due-label-today-and-tomorrow-are-soon
  (is (= {:text "today" :urgency :soon} (logic.date/due-label today today)))
  (is (= {:text "tomorrow" :urgency :soon} (logic.date/due-label (LocalDate/of 2026 9 29) today))))

(deftest due-label-two-to-seven-days-out-is-relative-and-later
  (is (= {:text "in 2d" :urgency :later} (logic.date/due-label (LocalDate/of 2026 9 30) today)))
  (is (= {:text "in 3d" :urgency :later} (logic.date/due-label (LocalDate/of 2026 10 1) today)))
  (is (= {:text "in 7d" :urgency :later} (logic.date/due-label (LocalDate/of 2026 10 5) today))))

(deftest due-label-beyond-seven-days-shows-the-iso-date
  (is (= {:text "2026-10-06" :urgency :later} (logic.date/due-label (LocalDate/of 2026 10 6) today)))
  (is (= {:text "2027-03-01" :urgency :later} (logic.date/due-label (LocalDate/of 2027 3 1) today))))

(deftest due-label-any-past-date-is-overdue
  (is (= {:text "1d overdue" :urgency :overdue} (logic.date/due-label (LocalDate/of 2026 9 27) today)))
  (is (= {:text "2d overdue" :urgency :overdue} (logic.date/due-label (LocalDate/of 2026 9 26) today)))
  (is (= {:text "40d overdue" :urgency :overdue} (logic.date/due-label (LocalDate/of 2026 8 19) today))))

(deftest due-label-crosses-month-and-year-boundaries
  (is (= {:text "tomorrow" :urgency :soon}
         (logic.date/due-label (LocalDate/of 2027 1 1) (LocalDate/of 2026 12 31))))
  (is (= {:text "1d overdue" :urgency :overdue}
         (logic.date/due-label (LocalDate/of 2026 12 31) (LocalDate/of 2027 1 1)))))

(deftest due-label-is-nil-without-a-due-date
  (is (nil? (logic.date/due-label nil today))))

(deftest next-day-is-the-following-calendar-day
  (is (= (LocalDate/of 2026 9 29) (logic.date/next-day today)))
  (is (= (LocalDate/of 2026 10 1) (logic.date/next-day (LocalDate/of 2026 9 30)))))

(deftest next-day-rolls-over-month-and-year-ends
  (is (= (LocalDate/of 2026 2 1) (logic.date/next-day (LocalDate/of 2026 1 31))))
  (is (= (LocalDate/of 2026 3 1) (logic.date/next-day (LocalDate/of 2026 2 28))))
  (is (= (LocalDate/of 2027 1 1) (logic.date/next-day (LocalDate/of 2026 12 31)))))

(deftest next-day-of-a-leap-day-is-the-first-of-march
  (is (= (LocalDate/of 2028 2 29) (logic.date/next-day (LocalDate/of 2028 2 28))))
  (is (= (LocalDate/of 2028 3 1) (logic.date/next-day (LocalDate/of 2028 2 29)))))
