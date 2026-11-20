(ns knope.adapters.task
  (:require [clojure.string :as str]
            [knope.logic.date :as logic.date]
            [knope.logic.task :as logic.task])
  (:import (java.time LocalDate LocalDateTime)
           (java.time.format DateTimeFormatter)))

(def ^:private created-at-format (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss"))

(def ^:private glyphs {:todo "[ ]" :doing "[~]" :done "[x]" :dropped "[-]"})

(defn- parse-date [s]
  (when s (LocalDate/parse s)))

(defn- format-date [^LocalDate d]
  (when d (str d)))

(defn parse-id
  [s]
  (let [id (parse-long s)]
    (if (and id (pos? id))
      id
      {:error/code :validation/bad-id :error/details {:input s}})))

(defn args->new-attrs
  [{:keys [title due note parent]} today]
  (let [due-date  (when due (logic.date/parse-date-input due today))
        parent-id (when parent (parse-id parent))]
    (cond
      (str/blank? title)
      {:error/code :validation/missing-title :error/details {}}

      (and due (nil? due-date))
      {:error/code :validation/bad-date :error/details {:input due}}

      (:error/code parent-id)
      parent-id

      :else
      {:title     title
       :note      (when-not (= "" note) note)
       :due-date  due-date
       :parent-id parent-id})))

(defn args->changes
  [{:keys [title due note clear-due]} today]
  (let [due-date (when due (logic.date/parse-date-input due today))]
    (cond
      (and (nil? title) (nil? due) (nil? note) (not clear-due))
      {:error/code :validation/nothing-to-edit :error/details {}}

      (and due clear-due)
      {:error/code :validation/conflicting-flags :error/details {}}

      (and title (str/blank? title))
      {:error/code :validation/missing-title :error/details {}}

      (and due (nil? due-date))
      {:error/code :validation/bad-date :error/details {:input due}}

      :else
      (cond-> {}
        title            (assoc :title title)
        due              (assoc :due-date due-date)
        clear-due        (assoc :due-date nil)
        (some? note)     (assoc :note (when-not (= "" note) note))))))

(defn args->day
  "The date to show: today when none is given."
  [{:keys [date]} today]
  (if (nil? date)
    today
    (or (logic.date/parse-date-input date today)
        {:error/code :validation/bad-date :error/details {:input date}})))

(defn row->task
  [{:keys [id parent_id title note status created_at due_date finish_date]}]
  (logic.task/make-task {:id          id
                         :parent-id   parent_id
                         :title       title
                         :note        note
                         :status      (keyword status)
                         :created-at  (LocalDateTime/parse created_at created-at-format)
                         :due-date    (parse-date due_date)
                         :finish-date (parse-date finish_date)}))

(defn task->params
  "Values for the parent_id, title, note, status, created_at, due_date and
  finish_date columns, in that order."
  [{:keys [parent-id title note status created-at due-date finish-date]}]
  [parent-id
   title
   note
   (name status)
   (.format ^LocalDateTime created-at created-at-format)
   (format-date due-date)
   (format-date finish-date)])

(defn task->display-row
  [{:keys [id title status due-date]} today {:keys [progress dimmed? parent-ref]}]
  (let [{:keys [text urgency]} (logic.date/due-label due-date today)]
    (cond-> {:glyph     (glyphs status)
             :id        id
             :title     title
             :due-label text
             :urgency   urgency}
      progress   (assoc :progress (str (:done progress) "/" (:planned progress)))
      dimmed?    (assoc :dimmed? true)
      parent-ref (assoc :parent-ref (str "↳ #" parent-ref)))))

(defn parse-page
  [s]
  (let [page (when s (parse-long s))]
    (cond
      (nil? s)        0
      (nat-int? page) page
      :else           {:error/code :validation/bad-page :error/details {:input s}})))

(defn search-match
  "The FTS match expression for typed text: each word that has a letter or
  digit, quoted as a prefix; or empty-search when none does."
  [text]
  (let [words (->> (str/split (str text) #"\s+")
                   (filter #(re-find #"[\p{L}\p{N}]" %)))]
    (if (empty? words)
      {:error/code :validation/empty-search :error/details {}}
      (str/join " " (map #(str "\"" (str/replace % "\"" "\"\"") "\"*") words)))))
