(ns jackalope.retrospective
  (:require [jackalope.issues :as is]
            [jackalope.zenhub :as zh]
            [jackalope.markdown :as md]
            [clojure.string :as str]
            [hiccup.core :as hic]))

(defn yes? [i]
  (= :yes (:do? i)))

(defn no? [i]
  (= :no (:do? i)))

(defn maybe? [i]
  (= :maybe (:do? i)))

(defn outcome
  "Determines which outcome applies to issue.
   The outcome is chosen based on the issue's :do? decision and :state.

   Potential outcomes are:
     :blocked          the issue was blocked during the sprint
     :done-as-planned  the plan said yes and the issue was completed
     :done-as-maybe    the plan said maybe and the issue was completed
     :not-done-maybe
     :late-add         wasn't included in the plan, but was completed 
                       (e.g., late-add or hotfix)
     :skipped-as-no
     :incomplete       the plan said yes but the issue was not completed

   returns :inscrutable if outcome could not be determined based on our rules"
  [issue]
  (cond
    (and (is/open? issue) (not (no? issue))
         (zh/blocked? issue))                :blocked
    (and (is/closed? issue) (yes? issue))     :done-as-planned
    (and (is/closed? issue) (maybe? issue))   :done-as-maybe
    (and (is/open? issue) (maybe? issue)
         (not (zh/epic? issue)))             :not-done-maybe
    (and (nil? (:do? issue))
         (is/closed? issue))                  :late-add
    (and (is/open? issue) (no? issue))        :skipped-as-no
    (and (is/open? issue) (yes? issue)
         (not (zh/epic? issue)))             :incomplete
    :else                                    :inscrutable))

(defn +outcome [issue]
  (assoc issue :outcome (outcome issue)))

(defn +downgraded?
  "decorates the issue with :downgraded=>true iff the outcome was :incomplete and
   the issue was changed during the sprint from an original 'yes' to 'maybe' or
   'no'"
  [issue]
  (if (and (= :incomplete (:outcome issue)) (is/open? issue) (zh/maybe? issue))
    (assoc issue :downgraded? true)
    issue))

(defn inum->i
  "Returns an index (as a hash-map) of issue number to :do?"
  [plan]
  (into {}
        (for [{:keys [number] :as p} plan] [number p])))

(defn +do? [i ndx]
  (assoc i :do? (get-in ndx [(:number i) :do?])))

(defn +est [i ndx]
  (if-let [e (get-in ndx [(:number i) :estimate])]
    (assoc i :estimate e)
    i))

(defn- mark [ndx]
  (fn [i]
    (-> i
        (+do? ndx)
        (+est ndx)
        +outcome
        +downgraded?)))

(defn retrospective
  "Returns a hash-map organized by outcome, where each key is a defined outcome
   and each value is a collection of issues that saw that outcome.
   (For more details on possible outcomes, see the outcome functionality above.)

   plan must be a valid representation of a sprint plan.

   issues must be an up-to-date collection of all issues currently associated
   to the milestone. this is the issue data that will be compared against the
   plan to determine outcome."
  [plan issues]
  (let [ndx (inum->i plan)]
    (->> issues
         (map (mark ndx))
         (group-by :outcome))))

(defn issues-url [sample-issue]
  (str/join "/"
            (-> sample-issue
                :html_url
                (str/split #"/")
                butlast)))

(defn table
  [issues]
  (list
   [:table
    {:style "border: 0; width: 90%"}
    [:tr {:align "left"} [:th "#"] [:th "assignee"] [:th "title"] [:th "milestone"]]
    (let [issues-url (issues-url (first issues))]
      (for [i (sort-by is/login issues)]
        [:tr
         [:td
          [:a
           {:href
            (str issues-url "/" (:number i))}
           (:number i)]]
         [:td (is/login i)]
         [:td (:title i)
          (when (zh/epic? i) [:i " (epic)"])
          (when (:downgraded? i) [:i (str " (downgraded to maybe)")])]
         [:td (get-in i [:milestone :title])]]))]))

(defn section-hic [issues heading]
  (when (not (empty? issues))
    (list
     [:h2 heading]
     [:p]
     (table issues)
     [:p])))

(def OUTCOMES
  [[:done-as-planned "Completed Planned Yes"]
   [:done-as-maybe   "Completed Planned Maybe"]
   [:late-add        "Completed Unplanned (Late Adds)"]
   [:incomplete      "Incomplete Planned Yes"]
   [:blocked         "Blocked"]])

(defn counts-hic [retro]
  [:table
   (for [[k v] OUTCOMES]
     [:tr
      [:td v] [:td (count (k retro))]])
   [:tr
    [:td "Total"] [:td (reduce + (map (comp count second) retro))]]])

(defn report-hic [retro]
  (list
   (for [[outcome heading] OUTCOMES]
     (section-hic (outcome retro) heading))
   [:hr]
   [:h2 "Summary"]
   (counts-hic retro)))

(defn report-html
  "retro must be a hash-map organized by outcome, where each key is a defined
   outcome and each value is a collection of issues that saw that outcome.
   E.g., the result of calling (retrospective ...).

   Returns an HTML representation of the retrospective report"
  [retro]
  (hic/html (report-hic retro)))

(defn make-retrospective-file [retro ms-title]
  (let [f (str ms-title ".retrospective.html")]
    (spit f (report-html retro))
    f))

(defn generate-report [plan issues ms-title]
  (let [retro  (retrospective plan issues)]
    (make-retrospective-file retro ms-title)))

(defn- ->md-table [issues]
  (md/table ["Estimate" "Assignee" "Title"]
            ;; one issue per row, sorted by assignee
            (for [{:keys [number estimate title] :as i}
                  (sort-by is/login issues)]
              [(str "#" number) estimate (is/login i) title])))

(defn- ->md [retro]
  (apply str
         (for [[k v] OUTCOMES]
           (apply str
                  "\n__" v ":__\n\n"
                  (if-let [issues (retro k)]
                    (format "%s\n" (->md-table issues))
                    "_(none)_\n")))))

(defn as-markdown [plan issues]
  (->md (retrospective plan issues)))
