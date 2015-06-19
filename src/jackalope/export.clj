(ns jackalope.export
  (:require [jackalope.core :as core]
            [clojure-csv.core :as csv]))

;; Export a CSV file of the specified milestone.
;; Formatted for easy import into a planning spreasheet.

(def COLS {0 {:fn #(str (:number %))}
           1 {:fn :title}
           2 {:fn #(core/who-nominated (:number %))}
           3 {:fn assignee}
           4 {:fn priority}
           5 {:fn loe}})

(defn labels [i]
  (map :name (:labels i)))

(defn assignee [i]
  (get-in i [:assignee :login]))

(defn parse-priority [l]
  (when (and (not (empty? l))
           (.startsWith l "P")
           (= 2 (count l)))
    (str (second l))))

(defn priority [i]
  (first (map parse-priority (labels i))))

(defn parse-loe [l]
  (when (and (not (empty? l))
             (.contains l "hour"))
    (.substring l 0 (- (count l)
                       (if (.endsWith l "s") 5 4)))))

(defn loe [i]
  (first (map parse-loe (labels i))))

(defn issue-row [i]
  (vec (for [[_ {f :fn}] (sort-by first COLS)] (or (f i) ""))))

(defn export-planning-sheet-csv [f issues]
  (spit f 
        (csv/write-csv (map issue-row issues))))
