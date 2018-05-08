(ns jackalope.core
  (:require [jackalope.github :as github]
            [jackalope.zenhub :as zenhub]
            [jackalope.issues :as issues]
            [jackalope.retrospective :as retro]
            [jackalope.markdown :as md]
            [clojure.set :as set]))

(def DEFAULT-CONF "github-prod.edn")

(defonce ^:private conn-atom (atom nil))

(defn +repo-id [c]
  (assoc c :repo-id (:id (github/fetch-repo c))))

(defn connect!
  ([cred-file]
     (let [c (read-string (slurp cred-file))]
       (assert (:user c) "Credentials must include :user for Github")
       (assert (:repo c) "Credentials must include :repo for Github")
       (assert (:github-token c) "Credentials must include :github-token")
       (reset! conn-atom (+repo-id c))
       true))
  ([]
     (connect! DEFAULT-CONF)))

(defn conn []
  (or  @conn-atom
       (throw (IllegalArgumentException.
               "API credentials must be initialized using 'connect!'"))))

(defn set-milestone [issue ms-num]
  (github/edit-issue (conn) {:number (:number issue)
                                    :milestone ms-num}))

(defn editor [ms-curr ms-next]
  (fn [{:keys [number do?] :as dec}]
    ;; Given a decision, determine what if anything should be
    ;; edited on the corresponding issue
    (merge
     {:number number
      :milestone (case do?
                   ;; TODO: if we stop using ms-curr, we'd just NOOP the yes issues
                   ;;       (a yes causes an unecessary edit, if we're using the
                   ;;        same milestone id for the curr release plan)
                   :yes   ms-curr
                   :no    ms-next
                   :maybe ms-curr
                   (throw (IllegalArgumentException. (format "Unrecognized decision: |%s|" do?))))}
     (when (= do? :maybe)
       {:label+ :maybe}))))

(defn actions-for
  "Composes github actions to make to issues, per the specified plan.

   Actions will *only* address issues in the specified plan. E.g., if there are
   issues in ms-curr that are not in the plan, no actions will be produced
   that change those issues.

   ms-curr should be the number of the milestone to be finalized per the plan.

   ms-next should be the number of the next milestone. I.e., the milestone that
   represents the next future sprint, to which we'll roll-over issues that we
   chose not to do in ms-curr. E.g. 175

   plan should be a collection of hash-maps where each hash-map represents a
   decision and contains these keys:
     :number Github issue number, e.g. 4123
     :do?    Team's decision regarding whether to do the work.
             Should be one of :yes, :no, :maybe
   Example hash-map:
     {:number 4123 :do? :yes}

   The composed actions represent these rules; for each issue in the plan:
   * Assign ms-curr milestone if :do? is :yes or :maybe
   * Assign ms-next milestone if :do? is :no
   * Add 'maybe' label if :do? is :maybe

   This function does not perform any of the actions. It returns the actions
   represented like so:

   {:edits [sequence of edit action representations, as hash-maps]
    :maybes [sequence of maybe label add representations, as hash-maps]"
  ;; TODO: use of ms-curr could be removed if the just-planned issues were
  ;; already assigned the current milestone (e.g., the nominations milestone).
  ;; ms-curr is only used right now as "the new milestone to move 'yes' issues
  ;; to and then consider active for the sprint
  [plan ms-curr ms-next]
  ;; TODO: if we stop using ms-curr, we'd just NOOP the yes issues
  (let [edits (map (editor ms-curr ms-next) plan)]
    {:edits edits
     ;; find maybes for adding the 'maybe' label
     :maybes (filter #(= :maybe (:label+ %)) edits)}))

(defn fetch-all-issues
  "Fetches and returns the union of issues assigned to the specified milestone
   and issues specified in plan"
  [ms-num plan]
  (let [conn (conn)
        msis (github/fetch-issues-by-milestone conn ms-num)
        setn  #(set (map :number %))
        mids (setn msis)
        pins (setn plan)
        mins (set/difference pins mids)
        adds (remove github/pull-request? (github/fetch-issues-by-nums conn mins))]
    (concat msis adds)))

(defn fetch-open-issues [ms-num]
  (->> (github/fetch-issues-by-milestone (conn) ms-num)
       (filter issues/open?)))

(defn assign-ms
  "Assigns the specified milestone (ms-num) to the specified issue (issue-num)"
 [issue-num ms-num]
  (github/edit-issue (conn) {:number issue-num
                                    :milestone ms-num}))
(defn unmaybe [inum]
  (github/remove-a-label (conn) inum :maybe))

(defn zh-do? [pipeline-name]
  (case pipeline-name
    "Nominated" :no
    "Maybe" :maybe
    "To Do" :yes
    "In Progress" :yes
    "Blocked" :yes
    "In Review" :yes
    "Pending" :yes
    "Closed" :yes ;; TODO! this results in assigning the next milestone, even tho it's DONE
    :inscrutable))

(defn pipeline->plan-recs [inum->i]
  (fn [{:strs [name issues]}]
    (for [i issues]
      (let [number (get i "issue_number")]
        (merge 
         {:number number
          :assignee (issues/assignee (get inum->i number))
          :do? (zh-do? name)}
         (when-let [e (get-in i ["estimate" "value"])]
           {:estimate e}))))))

(defn fetch-pipelines
  "Returns the ZenHub pipeline data for issue numbers in the specified milestone"
  [ms-num]
  (let [conn (conn)
        issue-nums (map :number (github/fetch-issues-by-milestone conn ms-num))]
    (zenhub/fetch-pipelines conn issue-nums)))

(defn as-inum->i [issues]
  (reduce (fn [m i]
            (assoc m (:number i) i))
          {}
          issues))

(defn as-plan
  "Fetches GitHub and ZenHub boards data for the specified milestone and returns
   a concise plan structure represented as a collection of hash-maps where each
   hash-map represents an issue and its decision in the plan.

   Example hash-map in the collection:
   {:number 6279,
    :estimate 3,
    :assignee 'dirtyvagabond',
    :title 'Add support for feature X',
    :do? :no}"
  [ms-num]
  (let [issues (github/fetch-issues-by-milestone (conn) ms-num)
        pipes (zenhub/fetch-pipelines (conn) (map :number issues))]
    (mapcat (pipeline->plan-recs (as-inum->i issues)) pipes)))

;;
;; Main use cases
;;

(defn plan!
  "Runs the specified actions, ostensibly based on a new sprint plan. 
   (See actions-for, for details on how the actions are composed.)"
  [{:keys [edits maybes]}]
  ;; TODO: This is really just a general 'run actions' function; rename?
  ;; TODO: return specific data that would be usable for fully undo-ing
  (let [conn (conn)]
    (doseq [e edits] (github/edit-issue conn e))
    (doseq [n (map :number maybes)]
      (github/add-a-label conn n :maybe))))

; TODO: support auto-gen of next milestone; stop asking for ms-next
(defn sweep-milestone
  "Given the current milestone id ms-curr and the next milestone id ms-next,
   returns action descriptions that will treat ms-curr as done and 'sweep'
   it into ms-next. The actions are not performed; this function only
   *describes* the actions as a proposal. (The sweep! function will
   perform the actions)

   The actions described will:
   1) clear 'maybe' labels from the issues in milestone ms-curr
   2) roll forward incomplete (non closed) issues from ms-curr to ms-next

   ms-curr should be the milestone number for the milestone to sweep. i.e., the 
   milestone that is being closed.

   ms-next should be the milestone number for the upcoming milestone. i.e., the
   milestone that is going to be planned next.

   Example call: (def S (sweep-milestone 197 198))

   The returned collection will contain hash-maps, where each hash-map specifies
   an action (a change to an issue). Example actions:

   ; A milestone assignation (presumably to sweep a ticket forward)
   {:number [issue number] 
    :action :assign-milestone
    :ms-num [milestone number]}

   ; An 'unmaybe', which removes the 'maybe' label
   {:number [issue number] 
    :action :unmaybe}"
  [ms-curr ms-next closed-ahead]
  ;; TODO: Possible optimization:
  ;; If a ticket was "maybe", is getting carried forward, and is also a "maybe"
  ;; in the new plan: leave the "maybe" label in place. (Currently, we clear
  ;; all "maybe" labels before carrying forward tickets, then assign "maybe"
  ;; labels, which can be redundant when the ticket already had the label.)
  ;; Caveat: The sweep-milestone step is currently conceptually separate from
  ;;   the plan! step. Not sure if it's worth coupling them for this
  (let [msis (doall (github/fetch-issues-by-milestone (conn) ms-curr))]
    (concat
     ;; issues closed in ms-next should be late-adds in ms-curr:
     (map (fn [n]
            {:number n
             :action :assign-milestone
             :ms-num ms-curr})
          (map :number closed-ahead))
     ;; incompletes should be rolled into ms-next:
     (map (fn [n]
            {:number n
             :action :assign-milestone
             :ms-num ms-next})
          (map :number (filter issues/open? msis)))
     ;; clear maybe labels in ms-curr:
     (map (fn [n]
            {:number n
             :action :unmaybe})
          (map :number (filter issues/has-maybe-label? msis))))))

(defn action= [v]
  (fn [a]
    (= v (:action a))))

(defn sweep!
  "Runs the specified actions. The actions must follow the structure returned by
   sweep-milestone."
  ;; TODO: provide API status/return for each action
  ;; need to clear *all* maybes when we do a milestone sweep. currently, we only
  ;; clear the ones from the closing milestone. if there are maybe labels in the
  ;; nominated milestone, they don't get cleared
  [actions]
  (doseq [{:keys [number]} (filter (action= :unmaybe) actions)]
    (unmaybe number))
  (doseq [{:keys [number ms-num]} (filter (action= :assign-milestone) actions)]
    (assign-ms number ms-num)))

(defn with-zenhub
  "Decorates each issue with ZenHub data, e.g. whether each issue is an epic.
   For issues marked as [:late-add true], will explicitly fetch the estimate
   from ZenHub and add as :estimate.
   (See zenhub/++ for more details.)

   Returns issues unchanged if we don't have zenhub credentials."
  [issues]
  (zenhub/++ (conn) issues))

(defn plan->table-md [plan do?]
  (md/table ["Number" "Title"]
            (for [{:keys [number title]}
                  (sort-by :number (filter #(= do? (:do? %)) plan))]
              [(str "#" number) title])))

; TODO: consider adding in, "what we said 'no' to".
;       (maybe when that list is better managed?)
(defn plan->github-markdown-table
  "Returns the specific plan, formatted as a table in GitHub markdown
   (presumably, to be posted as an issue comment)."
  [plan]
  (str
         "__Yes:__\n\n"
         (plan->table-md plan :yes)
         "\n\n"
         "__Maybe:__\n\n"
         (plan->table-md plan :maybe)))

(defn plan->ms-desc [plan]
  (format "<!--PLAN %s -->" (pr-str plan)))

(defn ms-desc->plan [s]
  (assert (> (count s) 9) "Plan's description is invalid (too short)")
  (read-string
   (subs s 9 (- (count s) 4))))

(defn +titles
  "For maybe and yes plan items, adds in a :title.
   Very expensive -- realizing the returned sequence will cause a call out to
   GitHub to fetches each individual issue in plan, one at a time, for all items
   that are 'maybe' or 'yes' in the plan."
  [plan]
  (let [conn (conn)]
    (map
     (fn [{:keys [number do?] :as p}]
       (if (contains? #{:maybe :yes} do?)
         (let [i (github/fetch-issue conn number)]
           (assoc p :title (:title i)))
         p))
     plan)))

;TODOL remove when no longer used(?)
(defn without
  "Removes the indicated issue from coll, assuming that coll is a collection of
   hash-maps each with a :number key. This allows us to leave open work issues
   in their home milestone (avoids sweeping or rolling them forward)."
 [{:keys [number]} coll]
  (remove #(= number (:number %)) coll))

(defn sprint-start**
  "Returns a description of the sprint start for the specified milestone."
  [ms-num]
  (let [ms (github/fetch-milestone (conn) ms-num)
        ms-num (:number ms)
        ms-title (:title ms)
        plan (+titles (as-plan ms-num))]
    {:ms-num ms-num
     :ms-title ms-title
     :plan plan
     :plan-tbl (plan->github-markdown-table plan)
     :plan-str (plan->ms-desc plan)}))

;TODO: remove in favour of sprint-start**
(defn sprint-start*
  "Returns a description of a sprint start, using the specified issue as the 
   sprint start request. The issue must already be assigned a valid milestone."
  [issue]
  (let [ms (:milestone issue)
        ms-num (:number ms)
        ms-title (:title ms)
        plan (without issue (+titles (as-plan ms-num)))]
    {:issue issue
     :ms-num ms-num
     :ms-title ms-title
     :plan plan
     :plan-tbl (plan->github-markdown-table plan)
     :plan-str (plan->ms-desc plan)}))

(comment
  (sprint-start*
   {:number 101
    :milestone {:number 3 :title "My favourite milestone!"}}))

(defn create-sprint-issue 
  [ms-num title]
  (github/create-issue (conn) title {:milestone ms-num}))

(defn do-sprint-start!
  "Given a sprint start request, performs the sprint start.

   Assumes the current milestone is ms-num.
   Assumes the next milestone already exists, as ms-num + 1.

   Performs these steps:
   * Composes a sprint plan based on data from GitHub & ZenHub
   * Moves 'no' items to next milestone
   * Writes a comment to the issue, showing the plan as a table
   * Sets the Milestone's description to contain the plan data
   * Closes the issue

   Returns the github issue associated with the sprint start."
  ;; TODO! remove issue param and always create
  ;; TODO! assert that ms-next exists, early.
  [{:keys [plan ms-num ms-title plan-tbl plan-str issue]}]
  (let [conn (conn)
        issue (or issue (create-sprint-issue ms-num "start sprint")) ;;TODO! always create new issue
        ms-curr ms-num
        ms-next (inc ms-curr)
        actions (actions-for plan ms-curr ms-next)
        comment (format
                 "The plan has %s decisions, resulting in %s edits and %s maybe labels..."
                 (count plan) (count (:edits actions)) (count (:maybes actions)))]
    (github/comment-on-issue conn issue comment)
    (plan! actions)
    (github/comment-on-issue conn issue
                             (format "Updated issues. The plan looks like...\n\n%s"
                                     plan-tbl))
    ;;TODO: don't destructively reset the Milestone's description.
    ;;      maybe fetch the desc and append? (requires careful handling)
    (github/set-milestone-desc conn ms-curr ms-title plan-str)
    (github/comment-on-issue 
     conn 
     issue
     "Saved the plan. The sprint has started.")
    (github/close-issue conn issue)
    issue))

(defn plan-from-ms [ms]
  (-> ms
      :description
      ms-desc->plan))

(defn mark-late-adds
  "Compares each issue in issues against plan. Returns issues, with any
   late add issue marked with :late-add true. 

   An issue is a late add if it is closed and the plan marked it as :do? :no, or
   it wasn't in the plan at all."
  [plan issues]
  (let [->nums #(into #{} (map :number %))
        las (set/difference (->nums (filter #(= "closed" (:state %)) issues))
                            (->nums (filter #(not= :no (:do? %)) plan)))
        late? #(contains? las (:number %))]
    ;;TODO: cleanup to be a 1 liner and always add :late-add
    (map 
     (fn [i]
       (if (late? i)
         (assoc i :late-add true)
         i))
     issues)))

(defn closed-in [ms-num]
  (github/fetch-closed-issues-by-milestone (conn) ms-num))

(defn union-i [a b]
  (map first (vals (group-by :number (concat a b)))))

(defn sprint-stop**
  "Returns a description of the sprint stop for the specified milestone."
  [ms-curr]
  (let [ms (github/fetch-milestone (conn) ms-curr)
        ms-next (inc ms-curr)
        ms-title (:title ms)
        plan    (ms-desc->plan (:description ms))
        cd-next (closed-in ms-next)
        issues  (union-i (fetch-all-issues ms-curr plan) cd-next)
        issues  (with-zenhub (mark-late-adds plan issues))
        actions (sweep-milestone ms-curr ms-next cd-next)
        retro   (retro/retrospective plan issues)]
    {:ms-num  ms-curr
     :ms-title ms-title
     :issues  issues
     :actions actions
     :plan    plan
     :retro   retro}))

;TODO: remove in favour of sprint-stop**
(defn sprint-stop* [issue]
  (let [ms (:milestone issue)
        ms-curr (:number ms)
        ms-next (inc ms-curr)
        ms-title (:title ms)
        plan    (ms-desc->plan (get-in issue [:milestone :description]))
        cd-next (closed-in ms-next)
        issues  (union-i (fetch-all-issues ms-curr plan) cd-next)
        issues  (with-zenhub (mark-late-adds plan issues))
        actions (without issue (sweep-milestone ms-curr ms-next cd-next))
        retro   (retro/retrospective plan issues)]
    {:issue   issue
     :ms-num  ms-curr
     :ms-title ms-title
     :issues  issues
     :actions actions
     :plan    plan
     :retro   retro}))

(defn do-reporting [{:keys [plan issues ms-num]} issue conn]
  (let [retro (retro/retrospective plan issues)
        ris (retro/as-issues retro)
        shouts (retro/shout-outs ris)
        outf (format "issues-retro.%s.edn" ms-num)]
    (doseq [[_ md] (retro/as-markdowns retro)]
      ;; add retro sections to issue, as markdown
      (github/comment-on-issue conn issue md))
    ;; shout outs
    (println "Shout outs:")
    (clojure.pprint/pprint shouts)
    ;; write file of retro issues
    (spit outf (pr-str ris))
    outf))

(defn do-sprint-stop!
  "Given a sprint stop request, performs the sprint stop.

   Assumes the next milestone already exists, as ms-num + 1.

   Performs these steps:
   * Sweeps unclosed issues to next milestone
   * Publishes retrospective info to the issue as a set of comments / tables
   * Closes the issue
   * Writes an issues/retro file in the form of issues records

   Returns the filename for the issues/retro file."
  ;; TODO! remove issue param and always create
  ;; TODO! assert that ms-next exists, early.
  [{:keys [issue actions plan issues ms-num] :as stop}]
  (let [conn (conn)
        ;;TODO! always create new issue
        issue (or issue (create-sprint-issue ms-num "stop sprint"))]
    (github/comment-on-issue conn issue
                             (format
                              "Sweeping issues (%s actions)... retrospective report on the way..."
                              (count actions)))

    (sweep! actions)
    (let [outf (do-reporting stop issue conn)]
      (github/comment-on-issue 
       conn 
       issue
       "Swept issues and filed retrospective report. The sprint is stopped.")
      (github/close-issue conn issue)
      outf)))

(defn find-work
  "Queries GitHub for assigned work that should be handled.
   Returns a hash-map like:
     :start  [collection of sprint start issues]
     :end    [collection of sprint end issues]"
  ;; TODO: isn't it weird to handle >1 starts, >1 stops?
  ;;       maybe introduce some logical enforcement of one at a time?
  [assignee]
  (merge
   (when-let [starts (:items (github/search-issues-assigned (conn)
                                                            assignee "start"))]
     {:start starts})
   (when-let [stops (:items (github/search-issues-assigned (conn)
                                                           assignee "stop"))]
     {:stop stops})))

;TODO! remove in favour of main's
(defn preview-sprint-start [{:keys [plan ms-num ms-title plan-tbl plan-str issue]}]
  (println "------ Sprint start preview ------")
  (println (format "Issue #%s" (:number issue)))
  (println (format "Milestone #%s, '%s'" ms-num ms-title))
  (println plan-tbl))

;TODO! remove in favour of main's
(defn print-outcomes [retro]
  (doseq [[outcome issues] retro] 
    (println "---" (name outcome) "---")
    (doseq [{:keys [number assignee title]} issues]
      (println number (:login assignee) title))))

;TODO! remove in favour of main's
(defn preview-sprint-stop [{:keys [issue actions plan issues]}]
  (println "------ Sprint stop preview ------")
  (println (format "Issue #%s" (:number issue)))
  (let [retro (retro/retrospective plan issues)
        ris (retro/as-issues retro)
        shouts (retro/shout-outs ris)
        ms (:milestone issue)]
    (println (format "Milestone #%s, '%s'" (:number ms) (:title ms)))
    (println "Outcomes:")
    (print-outcomes retro)
    (println "Shout outs:")
    (clojure.pprint/pprint shouts)
    (println "Actions:")
    (doseq [a actions]
      (println a))))

;TODO! remove once new start/stop CLI commands are done
(defn check-sprint [assignee preview?]
  (println "Checking sprint for" assignee)
  (let [work-issues (find-work assignee)]
    (doseq [i (:start work-issues)]
      (let [sprint-start (sprint-start* i)]
        (if preview?
          (preview-sprint-start sprint-start)
          (do
            (println (format "Starting a sprint, per issue #%s..."
                             (:number i)) "...")
            (github/comment-on-issue (conn) i "Starting the sprint...")
            (println (format "Processing plan with %s decisions..."
                             (count (:plan sprint-start))))
            (do-sprint-start! sprint-start)
            (println "Done.")))))
    (doseq [i (:stop work-issues)]
      (let [sprint-stop (sprint-stop* i)]
        (if preview?
          (preview-sprint-stop sprint-stop)
          (do
            (println (format "Stopping a sprint, per issue #%s..."
                             (:number i)) "...")
            (github/comment-on-issue (conn) i "Stopping the sprint...")
            (println (format "Processing %s issues from sprint plan..."
                             (count (:issues sprint-stop))))
            (let [outf (do-sprint-stop! sprint-stop)]
              (println (format "Did a Sprint Stop (#%s) by %s" (:number i) assignee))
              (println "Wrote issues/retro:" outf))))))))
