(ns jackalope.persist
  (:require [clojure.string :as str]))

(defn normalize-decision-text [t]
  (case (-> (if (empty? t) "" t)
            str/trim
            first
            str
            name
            str/lower-case)
    "y" :yes
    "d" :yes ;; already 'done'
    "n" :no
    "m" :maybe
    :inscrutable))

(defn ->decision [rec]
  ;;TODO: use header, not positional dependency
  {:number (Integer/parseInt (first rec))
   :do?    (normalize-decision-text (nth rec 6))})

(defn read-plan
  "Reads TSV formatted and headered file tsvf and returns a plan.
   A plan is a sequence of hash-maps where each hash-map
   represents a decision from the plan, e.g.:
     {:number 4841, :do? :yes}"
  [tsvf]
  (map ->decision
       (map 
        #(str/split % #"\t")
        (rest (str/split (slurp tsvf) #"\n")))))

(defn read-plan-from-edn [f]
  (read-string (slurp f)))

(defn read-plan-from-tsv [f]
  (map ->decision
       (map 
        #(str/split % #"\t")
        (str/split (slurp f) #"\n"))))

(defn import-plan
  "Reads the specified TSV (no header, please), converts to a Plan structure,
   saves that structure to outf as EDN, returns the structure."
  [tsvf outf]
  (let [plan (read-plan-from-tsv tsvf)]
    (spit outf (with-out-str (clojure.pprint/pprint plan)))
    plan))
