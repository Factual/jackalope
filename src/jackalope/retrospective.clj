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
     :late-add         (should already be marked as such)
                       (e.g., late-add or hotfix)
     :skipped-as-no
     :incomplete       the plan said yes but the issue was not completed

   returns :inscrutable if outcome could not be determined based on our rules"
  [issue]
  (cond
    (and (is/open? issue) (not (no? issue))
         (zh/blocked? issue))                :blocked
    (and (is/closed? issue) (yes? issue))    :done-as-planned
    (and (is/closed? issue) (maybe? issue))  :done-as-maybe
    (and (is/open? issue) (maybe? issue)
         (not (zh/epic? issue)))             :not-done-maybe
    (:late-add issue)                        :late-add
    (and (is/open? issue) (no? issue))       :skipped-as-no
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
         ;; decorate issues using plan/issues ndx
         (map (mark ndx))
         (group-by :outcome))))

(def OUTCOMES
  [[:done-as-planned "Completed Planned Yes"]
   [:done-as-maybe   "Completed Planned Maybe"]
   [:late-add        "Completed Unplanned (Late Adds)"]
   [:incomplete      "Incomplete Planned Yes"]
   [:blocked         "Blocked"]])

(defn- ->md-table [issues]
  (md/table ["Issue" "Estimate" "Assignee" "Title"]
            ;; one issue per row, sorted by assignee
            (for [{:keys [number estimate title] :as i}
                  (sort-by is/login issues)]
              [(str "#" number) estimate (is/login i) title])))

(defn as-markdowns
  "Returns the retrospective report as a hash-map keyed by outcome. Each value
   is the markdown representation of the corresponding outcome."
  [plan issues]
  (let [retro (retrospective plan issues)]
    (reduce
     (fn [acc [outcome-kw outcome-desc]]
       (assoc acc outcome-kw
              (apply str
                     "## " outcome-desc "\n"
                     (if-let [issues (retro outcome-kw)]
                       (format "%s\n" (->md-table issues))
                       "_(none)_\n"))))
     {}
     OUTCOMES)))
