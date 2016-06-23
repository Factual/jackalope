(ns jackalope.core
  (:require [jackalope.github :as github]
            [jackalope.issues :as issues]
            [jackalope.persist :as pst]
            [jackalope.retrospective :as retro]
            [clojure.set :as set]))

(defonce ^:private github-atom (atom nil))

(defn github!
  "Sets your authentication with Github. If cred-file is supplied, reads from
   that. Otherwise expects your credentials to be in resources/github.edn.
   Credentials should be like:
     {:auth 'login:pwd'
      :user 'MyOrg'
      :repo 'myrepo'}"
  ([cred-file]
     (let [c (read-string (slurp cred-file))]
       (assert (:auth c) "Github credentials must include :auth")
       (assert (:user c) "Github credentials must include :user")
       (assert (:repo c) "Github credentials must include :repo")
       (reset! github-atom c)
       true))
  ([]
     (github! "github-prod.edn")))

(defn github-conn []
  (or  @github-atom
       (throw (IllegalArgumentException.
               "Github API credentials must be initialized using 'github!'"))))

(defn set-milestone [i ms-num]
  (github/edit-issue (github-conn) {:number (:number i)
                                    :milestone ms-num}))

(defn editor [ms-curr ms-next]
  (fn [{:keys [number do?] :as dec}]
    ;; Given a decision, determine what if anything should be
    ;; edited on the corresponding issue
    (merge
     {:number number
      :milestone (case do?
                   ;; TODO: if we stop using ms-curr, we'd just NOOP the yes issues
                   :yes   ms-curr
                   :no    ms-next
                   :maybe ms-curr)}
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

(defn nominated-by [issue-num]
  (let [last-ms-event (->> issue-num
                           (github/fetch-issue-events (github-conn))
                           (filter #(= "milestoned" (:event %)))
                           last)]
    (get-in last-ms-event [:actor :login])))

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


;;
;; Main use cases
;;

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

   ms-num should be the number of the milestone to be finalized per the plan.

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
  ;; TODO: return specific data that would be usable for fully undo-ing
  ;; TODO: use of ms-curr could be removed if the just-planned issues were 
  ;; already assigned the current milestone (e.g., the nominations milestone).
  ;; ms-curr is only used right now as "the new milestone to move 'yes' issues
  ;; to and then consider active for the sprint"
  (let [{:keys [maybes edits] :as eds} (edits-from plan ms-curr ms-next)
        conn (github-conn)]
    (doseq [e edits] (github/edit-issue conn e))
    (doseq [n (map :number maybes)]
      (github/add-a-label conn n :maybe))
    {:edits edits
     :maybes maybes}))

(defn sweep-milestone
  "Given the current milestone id ms-curr and the next milestone id ms-next,
   returns action descriptions that will treat ms-curr as done and 'sweep'
   it into ms-next. The actions are not performed; this function only
   *describes* the actions as a proposal. (The sweep! function will perform the 
   actions)

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
  (let [msis (doall (github/fetch-issues-by-milestone (github-conn) ms-curr))]
    (concat
     (map (fn [n]
            {:number n
             :action :assign-milestone
             :ms-num ms-next})
          (map :number (filter issues/open? msis)))
     (map (fn [n]
            {:number n
             :action :unmaybe})
          (map :number (filter issues/has-maybe-label? msis))))))

(defn action= [v]
  (fn [a]
    (= v (:action a))))

(defn sweep!
  "Runs the specfiied actions. The actions must follow the structure returned by
   sweep-milestone."
  ;; TODO: provide API status/return for each action
  [actions]
  (doseq [{:keys [number]} (filter (action= :unmaybe) actions)]
    (unmaybe number))
  (doseq [{:keys [number ms-num]} (filter (action= :assign-milestone) actions)]
    (assign-ms number ms-num)))

(defn generate-retrospective-report
  "Generates a retrospective report, using the specified milestone and saved
   plan. Saves the report as HTML to a local file. Returns the filename."
 [ms-num ms-title]
  (let [plan   (pst/read-plan-from-edn (str ms-title ".plan.edn"))
        issues (fetch-all-issues ms-num plan)]
    (retro/generate-report plan issues ms-title)))

(comment 
  ;; Example of importing and finalizing a plan
  (github!)
  (def PLAN (import-plan-from-json "16.04.1"))
  (doseq [d PLAN] (println d))
  (def MS-CURR 214) ;just planned
  (def MS-NEXT 215) ;future sprint
  ;;; be sure you really want to do this!
  ;;; (def RES (plan! PLAN MS-CURR MS-NEXT))
)

(comment
  ;; Example of sweeping a milestone:
  (github!)
  (def MS-CURR 214) ;just completed
  (def MS-NEXT 215) ;next sprint
  (def ACTIONS (sweep-milestone MS-CURR MS-NEXT))
  (doseq [a ACTIONS] (println a))
  ;;; be sure you really want to do this!
  ;;; (sweep! ACTIONS)
)


