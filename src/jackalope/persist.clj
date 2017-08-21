(ns jackalope.persist
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [jackalope.zenhub :as zenhub]))

(defn fname-json [ms-title]
  (str ms-title ".plan.json"))

(defn fname-edn [ms-title]
  (str ms-title ".plan.edn"))

(defn normalize-decision-text [t]
  (case (-> (if (and (not (keyword? t)) (empty? t)) "" (name t))
            str/trim
            first
            str
            str/lower-case)
    "y" :yes
    "d" :yes ;; already 'done'
    "c" :yes ;; already 'closed'
    "n" :no
    "m" :maybe
    :inscrutable))

(defn ->decision [rec]
  ;;TODO: use header, not positional dependency
  {:number (Integer/parseInt (first rec))
   :do?    (normalize-decision-text (nth rec 6))})

(defn read-plan-from-edn [f]
  (read-string (slurp f)))

(defn read-plan [ms-title]
  (read-plan-from-edn (fname-edn ms-title)))

(defn import-plan-from-json
  "Reads the specified plan as a JSON file, converts to a Plan structure,
   saves that structure to outf as EDN, returns the structure.
  
   The 1 arg variation takes a release name (e.g., '16.01.2'), reads the
   corresponding JSON file (e.g., '16.01.2.plan.json'), and saves the EDN
   version (e.g., '16.01.2.plan.edn'). The JSON file is expected to be the
   format we import from Zenhub."
  ([f-json f-edn]
   (let [plan (zenhub/as-plan (json/read-str (slurp f-json)))]
     (spit f-edn (with-out-str (clojure.pprint/pprint plan)))
     plan))
  ([ms-title]
   (import-plan-from-json 
    (fname-json ms-title)
    (fname-edn ms-title))))

(defn save-plan-from-zenhub
  "Returns a hash-map like:
   {:plan-json [JSON filename]
    :plan-edn  [EDN filename]
    :plan      [PLAN (as a structure)]"
  [ms-title boards]
  (let [f-json (fname-json ms-title)
        f-edn  (fname-edn ms-title)]
    (spit f-json (json/json-str boards))
    (let [plan (import-plan-from-json f-json f-edn)]
      {:plan-json f-json
       :plan-edn f-edn
       :plan plan})))
