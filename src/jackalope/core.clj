(ns jackalope.core
  (:require [jackalope.github :as github]
            [jackalope.zenhub :as zenhub]
            [jackalope.issues :as issues]
            [jackalope.retrospective :as retro]
            [jackalope.markdown :as md]
            [clojure.set :as set]))

(def DEFAULT-CONF "github-prod.edn")

(defonce ^:private github-atom (atom nil))

(defn github!
  "Sets your authentication with Github. If cred-file is supplied, reads from
   that. Otherwise expects your credentials to be in resources/[DEFUALT-CONF].
   Credentials should be like:
     {:user 'a_user_or_org'
      :repo 'a_repo'
      :github-token 'a_long_token_string_generated_via_your_github_acct'}"
;; TODO: consider fetching repo-id and adding to conf. 
;;       note that this would make github! non-lazy
  ([cred-file]
     (let [c (read-string (slurp cred-file))]
       (assert (:user c) "Github credentials must include :user")
       (assert (:repo c) "Github credentials must include :repo")
       (assert (:github-token c) "Github credentials must include :github-token")
       (reset! github-atom c)
       true))
  ([]
     (github! DEFAULT-CONF)))

(defn github-conn []
  (or  @github-atom
       (throw (IllegalArgumentException.
               "Github API credentials must be initialized using 'github!'"))))

(defn zenhub? []
  (get (github-conn) :zenhub-token))

(defn set-milestone [issue ms-num]
  (github/edit-issue (github-conn) {:number (:number issue)
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

(defn edits-from
  "Composes updates to make to issues, per the specified plan"
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
  (let [conn (github-conn)
        msis (github/fetch-issues-by-milestone conn ms-num)
        setn  #(set (map :number %))
        mids (setn msis)
        pins (setn plan)
        mins (set/difference pins mids)
        adds (remove github/pull-request? (github/fetch-issues-by-nums conn mins))]
    (concat msis adds)))

(defn fetch-open-issues [ms-num]
  (->> (github/fetch-issues-by-milestone (github-conn) ms-num)
       (filter issues/open?)))

(defn assign-ms
  "Assigns the specified milestone (ms-num) to the specified issue (issue-num)"
 [issue-num ms-num]
  (github/edit-issue (github-conn) {:number issue-num
                                    :milestone ms-num}))
(defn unmaybe [inum]
  (github/remove-a-label (github-conn) inum :maybe))

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

(defn pipeline->plan-recs [{:strs [name issues]}]
(for [i issues]
    (merge 
     {:number (get i "issue_number")
      :do? (zh-do? name)}
     (when-let [e (get-in i ["estimate" "value"])]
       {:estimate e}))))

(defn fetch-pipelines
  "Returns the ZenHub pipeline data for issue numbers in the specified milestone"
  [ms-num]
  (let [{:keys [repo zenhub-token] :as gc} (github-conn)
        repo-id (:id (github/fetch-repo gc))
        issue-nums (map :number (github/fetch-issues-by-milestone
                                           (github-conn) ms-num))]
    (zenhub/fetch-pipelines zenhub-token repo-id issue-nums)))

(defn as-plan
  "Takes ZenHub boards data and converts to a concise plan structure; returns a
   collection of hash-maps where each hash-map represents an issue and its
   decision in the plan.

   Example hash-map in the collection:
   {:number 6279,
    :estimate 3,
    :title 'Add support for feature X',
    :do? :no}"
;;TODO!! we don't get the title here; merge in elsewhere and filter out unwanted issues
  [pipelines]
  (mapcat pipeline->plan-recs pipelines))

;; TODO: should much of this live in the zenhub namespace (law of Demeter?)
(defn fetch-plan-from-zenhub [ms-num]
  (as-plan (fetch-pipelines ms-num)))

;;
;; Main use cases
;;

(defn plan* [plan ms-curr ms-next]
  (let [{:keys [maybes edits]} (edits-from plan ms-curr ms-next)]
    {:edits edits
     :maybes maybes}))

(defn plan!
  "Updates the specified Github repo issues based on the specified plan.
   This *only* addresses issues in the specified plan. E.g., if there are
   issues assigned ms-curr that are not in the plan, those issues will
   remain in ms-curr, untouched.

   For each issue in the plan:
   * Assigns ms-curr milestone if :do? is :yes or :maybe
   * Assigns ms-next milestone if :do? is :no
   * Adds 'maybe' label if :do? is :maybe

   The first argument must contain all necessary 'connection' data.

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

   Returns a report as a hash-map:
   {:edited [sequence of results from each issue edit]
    :maybes [sequence of results from each maybe label add]"
  [plan ms-curr ms-next]
  ;; TODO: change to take output of plan* (actions) 
  ;; TODO: return specific data that would be usable for fully undo-ing
  ;; TODO: use of ms-curr could be removed if the just-planned issues were 
  ;; already assigned the current milestone (e.g., the nominations milestone).
  ;; ms-curr is only used right now as "the new milestone to move 'yes' issues
  ;; to and then consider active for the sprint"
  (let [{:keys [maybes edits] :as eds} (plan* plan ms-curr ms-next)
        conn (github-conn)]
    (doseq [e edits] (github/edit-issue conn e))
    (doseq [n (map :number maybes)]
      (github/add-a-label conn n :maybe))
    eds))

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
  [ms-curr ms-next]
  ;; TODO: Possible optimization:
  ;; If a ticket was "maybe", is getting carried forward, and is also a "maybe"
  ;; in the new plan: leave the "maybe" label in place. (Currently, we clear
  ;; all "maybe" labels before carrying forward tickets, then assign "maybe"
  ;; labels, which can be redundant when the ticket already had the label.)
  ;; Caveat: The sweep-milestone step is currently conceptually separate from
  ;;   the plan! step. Not sure if it's worth coupling them for this
  (let [msis (doall (github/fetch-issues-by-milestone (github-conn) ms-curr))
        closed-ahead (github/fetch-closed-issues-by-milestone (github-conn) ms-next)]
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
   (See zenhub/++ for more details.)
   Returns issues unchanged if we don't have zenhub credentials."
  [issues]
  (if (zenhub?)
    (let [gc (github-conn)
          zenhub-token (:zenhub-token gc)
          repo-id (:id (github/fetch-repo gc))]
      (zenhub/++ zenhub-token repo-id issues))
    issues))

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
         "I've saved the plan!\n\n"
         "__Yes:__\n\n"
         (plan->table-md plan :yes)
         "\n\n"
         "__Maybe:__\n\n"
         (plan->table-md plan :maybe)))

(defn plan->ms-desc [plan]
  (format "<!--PLAN %s -->" (pr-str plan)))

(defn ms-desc->plan [s]
  (read-string
   (subs s 9 (- (count s) 4))))

(defn +titles
  "For maybe and yes plan items, adds in a :title.
   Very expensive -- realizing the returned sequence will cause a call out to
   GitHub to fetches each individual issue in plan, one at a time, for all items
   that are 'maybe' or 'yes' in the plan."
  [plan]
  (let [conn (github-conn)]
    (map
     (fn [{:keys [number do?] :as p}]
       (if (contains? #{:maybe :yes} do?)
         (let [i (github/fetch-issue conn number)]
           (assoc p :title (:title i)))
         p))
     plan)))

(defn without
  "Removes the indicated issue from coll, assuming that coll is a collection of
   hash-maps each with a :number key. This allows us to leave open work issues
   in their home milestone (avoids sweeping or rolling them forward)."
 [{:keys [number]} coll]
  (remove #(= number (:number %)) coll))

(defn do-sprint-start!
  "Handles a request to start a sprint, as specified in issue. The issue must
   already be assigned a valid Milestone.

   Performs these steps:
   * Composes a sprint plan based on data from GitHub & ZenHub
   * Moves 'no' items to next milestone
   * Writes a comment to the issue, showing the plan as a table
   * Sets the Milestone's description to contain the plan data
   * Closes the issue"
  ;;TODO: don't destructively reset the Milestone's description.
  ;;      maybe fetch the desc and append? (requires careful handling)
  [issue]
  (let [ms (:milestone issue)
        ms-num (:number ms)
        ms-title (:title ms)
        _ (println "Fetching plan...")
        plan (without issue (+titles (fetch-plan-from-zenhub ms-num)))
        plan-tbl (plan->github-markdown-table plan)
        plan-str (plan->ms-desc plan)]
    (println "Running plan on repo...")
    (plan! plan ms-num (inc ms-num))
    (github/comment-on-issue (github-conn) issue plan-tbl)
    (github/set-milestone-desc (github-conn) ms-num ms-title plan-str)
    (github/close-issue (github-conn) issue)
    plan))

(defn plan-from-ms [ms]
  (-> ms
      :description
      ms-desc->plan))

(defn do-sprint-stop-comments [plan issues issue]
  (doseq [[_ md] (retro/as-markdowns plan issues)]
    (github/comment-on-issue (github-conn) issue md)))

;TODO: to be complete, for any issue that we didn't record estimate data for in
;      original plan, need to fetch individually from ZenHub before generating
;      retro report (or live with 'missing' estimate data in retro)
(defn do-sprint-stop! [issue]
  (let [ms-num (get-in issue [:milestone :number])
        ms (github/fetch-milestone (github-conn) ms-num)
        plan (plan-from-ms ms)
        issues (with-zenhub (fetch-all-issues ms-num plan))
        actions (without issue (sweep-milestone ms-num (inc ms-num)))]
    (sweep! actions)
    (do-sprint-stop-comments plan issues issue)
    (github/close-issue (github-conn) issue)
    {:milestone ms
     :actions actions
     :plan plan
     :issues issues}))

(defn find-work
  "Queries GitHub for assigned work that should be handled.
   Returns a hash-map like:
     :start  [collection of sprint start issues]
     :end    [collection of sprint end issues]"
  ;; TODO: isn't it weird to handle >1 starts, >1 stops?
  ;;       maybe introduce some logical enforcement of one at a time?
  [assignee]
  (merge
   (when-let [starts (:items (github/search-issues-assigned (github-conn)
                                                            assignee "start"))]
     {:start starts})
   (when-let [stops (:items (github/search-issues-assigned (github-conn)
                                                           assignee "stop"))]
     {:stop stops})))

(defn check-sprint [assignee]
  (println "Checking sprint for" assignee)
  (let [work-issues (find-work assignee)]
    (println "Work issues:" work-issues)
    (doseq [i (:start work-issues)]
      (println (format "Doing a Sprint Start (#%s) by %s" (:number i) assignee) "...")
      (do-sprint-start! i)
      (println "Done."))
    (doseq [i (:stop work-issues)]
      (do-sprint-stop! i)
      (println (format "Did a Sprint Stop (#%s) by %s" (:number i) assignee)))))


