(ns gaoshun.logic.date-test
  (:require [clojure.test :refer [deftest is testing]]
            [gaoshun.logic.date :as logic.date])
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
             "2026-13-01" "2026-02-30" "2026-9-1" "10/01/2026" nil]]
    (testing (pr-str s)
      (is (nil? (logic.date/parse-date-input s today))))))
