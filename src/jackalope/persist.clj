(ns jackalope.persist
  (:require [clojure.string :as str]
            [clojure.data.json :as json]))

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

(defn as-int [v]
  (Integer/parseInt (str v)))

(defn read-issues-from-zenhub-json
  "Reads in the raw JSON exported from ZenHub.

   Expects the format created by HongHao's 'zh dumper' extension or ZenHub's own
   format. 
   TODO: only use ZenHub format.

   Returns a hash-map where keys are the pipelines and values are a collection of issue 
   numbers, representing the issues in that pipeline. Like:
     :nominated  [numbers for the issues still nominated, so those are a 'no']
     :maybe      [numbers for the issues identified as Maybes]
     :todo       [numbers for the issues selected as yes]
     :inprogress [numbers for the issues already being worked on]
     :pending    [numbers for the issues being worked on but maybe blocked]
     :closed     [numbers for the issues that are already done]"
  [f]
  (into {}
        (for [p (get (json/read-str (slurp f)) "pipelines")]
          [(-> (get p "name") 
               (str/replace #"\s" "")
               str/lower-case
               keyword)
           (map #(as-int (get % "issue_number"))
                (get p "issues"))])))

(defn read-plan-from-json
  "Reads a sprint plan from JSON exported from ZenHub and returns a collection of 
   hash-maps where each hash-map represents an issue and its decision in the plan.

   Example hash-map in the collection:
   {:number 6279, :do? :no}"
  [f]
  (mapcat
   (fn [[p nums]]
    (for [n nums]
      {:number n
       :do? (case p
              :nominated :no
              :maybe :maybe
              :todo :yes
              :inprogress :yes
              :blocked :yes
              :inreview :yes
              :pending :yes
              :closed :yes  ;; TODO! this results in assigning the next milestone, even tho it's DONE
              :inscrutable)}))
   (read-issues-from-zenhub-json f)))

(defn import-plan-from-json
  "Reads the specified plan as a JSON file converts to a Plan structure,
   saves that structure to outf as EDN, returns the structure.
  
   The 1 arg variation takes a release name (e.g., '16.01.2'), reads the
   corresponding JSON file (e.g., '16.01.2.plan.json'), and saves the EDN
   version (e.g., '16.01.2.plan.edn'). The JSON file is expected to be the
   format we import from Zenhub."
  ([f-json f-edn]
   (let [plan (read-plan-from-json f-json)]
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
