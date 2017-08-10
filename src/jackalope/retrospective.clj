(ns jackalope.retrospective
  (:require [jackalope.issues :as i]
            [jackalope.zenhub :as zh]
            [clojure.string :as str]
            [hiccup.core :as hic]))

(defn yes? [i]
  (= :yes (:do? i)))

(defn no? [i]
  (= :no (:do? i)))

(defn maybe? [i]
  (= :maybe (:do? i)))

(defn number->do?-ndx
  "Returns an index (as a hash-map) of issue number to :do?"
  [plan]
  (into {} (map (juxt :number :do?) plan)))

(defn +do? [i ndx]
  (assoc i :do? (get ndx (:number i))))

(defn outcome
  "Determines which outcome applies to issue.
   The outcome is chosen based on the issue's :do? decision and :state.

   Potential outcomes are:
     :blocked
     :done-as-planned
     :done-as-maybe
     :not-done-maybe
     :late-add
     :skipped-as-no
     :incomplete

   returns :inscrutable if outcome could not be determined based on our rules"
  [issue]
  (cond
    (and (i/open? issue)
         (zh/blocked? issue))                :blocked
    (and (i/closed? issue) (yes? issue))     :done-as-planned
    (and (i/closed? issue) (maybe? issue))   :done-as-maybe
    (and (i/open? issue) (maybe? issue)
         (not (zh/epic? issue)))             :not-done-maybe
    (and (nil? (:do? issue))
         (i/closed? issue))                  :late-add
    (and (i/open? issue) (no? issue))        :skipped-as-no
    (and (i/open? issue) (yes? issue)
         (not (zh/epic? issue)))             :incomplete
    :else                                    :inscrutable))

(defn +outcome [issue]
  (assoc issue :outcome (outcome issue)))

(defn +downgraded?
  "decorates the issue with :downgraded=>true iff the outcome was :incomplete and
   the issue was changed during the sprint from an original 'yes' to 'maybe' or
   'no'"
  [issue]
  (if (and (= :incomplete (:outcome issue)) (i/open? issue) (zh/maybe? issue))
    (assoc issue :downgraded? true)
    issue))

(defn retrospective
  "Returns a hash-map organized by outcome, where each key is a defined outcome
   and each value is a collection of issues that saw that outcome. Outcomes:

     :done-as-planned  the plan said yes and the issue was completed
     :done-as-maybe    the plan said maybe and the issue was completed
     :incomplete       the plan said yes but the issue was not completed
     :late-add         wasn't included in the plan, but was completed 
                       (e.g., late-add or hotfix)

   argument must be a hash-map with :plan and :issues, representing the raw
   data required for a retrospective.

   :issues must be an up-to-date collection of all issues currently associated
   to the milestone. this is the issue data that will be compared against the
   plan to determine outcome."
  [plan issues]
  (let [ndx (number->do?-ndx plan)
        issues (map #(+do? % ndx) issues)]
    (->> issues
         (map +outcome)
         (map +downgraded?)
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
      (for [i (sort-by #(get-in % [:assignee :login]) issues)]
        [:tr
         [:td
          [:a
           {:href
            (str issues-url "/" (:number i))}
           (:number i)]]
         [:td (get-in i [:assignee :login])]
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
