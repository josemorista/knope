(ns gaoshun.adapters.task
  (:require [clojure.string :as str]
            [gaoshun.logic.date :as logic.date]
            [gaoshun.logic.task :as logic.task])
  (:import (java.time LocalDate LocalDateTime)
           (java.time.format DateTimeFormatter)))

(def ^:private created-at-format (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss"))

(def ^:private glyphs {:todo "[ ]" :doing "[~]" :done "[x]" :dropped "[-]"})

(defn- parse-date [s]
  (when s (LocalDate/parse s)))

(defn- format-date [^LocalDate d]
  (when d (str d)))

(defn args->new-attrs
  [{:keys [title due note]} today]
  (let [due-date (when due (logic.date/parse-date-input due today))]
    (cond
      (str/blank? title)
      {:error/code :validation/missing-title :error/details {}}

      (and due (nil? due-date))
      {:error/code :validation/bad-date :error/details {:input due}}

      :else
      {:title    title
       :note     (when-not (= "" note) note)
       :due-date due-date})))

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
  [{:keys [id title status due-date]} today _extras]
  (let [{:keys [text urgency]} (logic.date/due-label due-date today)]
    {:glyph     (glyphs status)
     :id        id
     :title     title
     :due-label text
     :urgency   urgency}))

(defn parse-page
  [s]
  (let [page (when s (parse-long s))]
    (cond
      (nil? s)        0
      (nat-int? page) page
      :else           {:error/code :validation/bad-page :error/details {:input s}})))
