(ns jackalope.retrospective
  (:require [hiccup.core :as hic]))

(defn closed? [i]
  (= "closed" (:state i)))

(def open?
  (complement closed?))

(defn yes? [i]
  (= :yes (:do? i)))

(defn no? [i]
  (= :no (:do? i)))

(defn maybe? [i]
  (= :maybe (:do? i)))

(defn +do?-from-plan
  "Returns issues with :do? added in from plan"
  [plan issues]
  (let [idx (into {} (map (juxt :number :do?) plan))]
    (for [{:keys [number] :as i} issues]
      (assoc i :do? (get idx number)))))

(defn outcome
  "Determines which outcome applies to issue.
   The outcome is chosen based on the issue's :do? decision and :state.

   Potential outcomes are:
     :done-as-planned
     :done-as-maybe
     :not-done-maybe
     :late-add
     :skipped-as-no
     :incomplete

   returns :inscrutable if outcome could not be determined based on our rules"
  [issue]
  (cond
   (and (closed? issue) (yes? issue))     :done-as-planned
   (and (closed? issue) (maybe? issue))   :done-as-maybe
   (and (open? issue) (maybe? issue))     :not-done-maybe
   (or (nil? (:do? issue))
       (and (closed? issue) (no? issue))) :late-add
   (and (open? issue) (no? issue))        :skipped-as-no
   (and (open? issue) (yes? issue))       :incomplete
   :else :inscrutable))

(defn +outcome [issue]
  (assoc issue :outcome (outcome issue)))

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
  [{:keys [plan issues]}]
  (->> issues
       (+do?-from-plan plan)
       (map +outcome)
       (group-by :outcome)))

(defn table
  "issues-url is used to constructe the specific issue link. should be like:
   http://github.com/some_user/some_repo/issues/"
  [issues issues-url]
  (list
   [:table
    {:style "border: 0; width: 90%"}
    [:tr {:align "left"} [:th "#"] [:th "assignee"] [:th "title"] [:th "milestone"]]
    (for [i (sort-by #(get-in % [:assignee :login]) issues)]
      [:tr
       [:td
        [:a
         {:href
          (str issues-url (:number i))}
         (:number i)]]
       [:td (get-in i [:assignee :login])]
       [:td (:title i)]
       [:td (get-in i [:milestone :title])]])]))

(defn section-hic [issues heading issues-url]
  (when (not (empty? issues))
    (list
     [:h2 heading]
     [:p]
     (table issues issues-url)
     [:p])))

(def OUTCOMES
  [[:done-as-planned "Completed As Planned Yes"]
   [:done-as-maybe   "Completed As Planned Maybe"]
   [:late-add        "Completed As Late Add"]
   [:not-done-maybe  "Maybes Left Undone"]
   [:skipped-as-no   "Skipped as No"]
   [:incomplete      "Incomplete"]])

(defn counts-hic [retro]
  [:table
   (for [[k v] OUTCOMES]
     [:tr
      [:td v] [:td (count (k retro))]])
   [:tr
    [:td "Total"] [:td (reduce + (map (comp count second) retro))]]])

(defn report-hic [retro issues-url]
  (list
   (for [[outcome heading] OUTCOMES]
     (section-hic (outcome retro) heading issues-url))
   [:hr]
   [:h2 "Summary"]
   (counts-hic retro)))

(defn report-html
  "retro must be a hash-map organized by outcome, where each key is a defined
   outcome and each value is a collection of issues that saw that outcome.
   E.g., the result of calling (retrospective ...).

   Returns an HTML representation of the retrospective report"
  [retro issues-url]
  (hic/html (report-hic retro issues-url)))
