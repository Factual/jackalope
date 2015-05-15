(ns jackalope.core
  (:require [jackalope.github :as github]
            [jackalope.retrospective :as retro]
            [clojure.set :as set]))

(def ^:private github-atom (atom nil))

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
       (reset! github-atom c)))
  ([]
     (github! "resources/github.edn")))

(defn github-conn []
  (or  @github-atom
       (throw (IllegalArgumentException.
               "Github API credentials must be initialized using 'github!'"))))

(defn editor-from [ms-curr ms-next]
  (fn [{:keys [number do?] :as dec}]
    ;; Given a decision, determine what if anything should be
    ;; edited on the corresponding issue
    (merge
     {:number number
      :milestone (case do?
                   :yes   ms-curr
                   :no    ms-next
                   :maybe ms-curr)}
     (when (= do? :maybe)
       {:label+ :maybe}))))

(defn edits-from
  "Composes updates to make to issues, per the specified plan"
  [plan ms-curr ms-next]
  (let [edits (map (editor-from ms-curr ms-next) plan)]
    {:edits edits
     ;; find maybes for adding the 'maybe' label
     :maybes (filter #(= :maybe (:label+ %)) edits)}))

(defn do-decisions
  "Updates the specified Github repo issues based on the specified plan.

   The first argument must contain all necessary 'connection' data.

   ms-curr is number of the current milestone. I.e., the milestone of the sprint
   that we've just planned. E.g. 174

   ms-next is the number of the next milestone. I.e., the milestone that 
   represents the next future sprint, to which we'll roll-over issues that we
   chose not to do in ms-curr. E.g. 175
   
   plan is a collection of hash-maps where each hash-map represents a 
   decision and contains these keys:
     :number Github issue number, e.g. 4123
     :do?    Team's decision regarding whether to do the work.
             Should be one of :yes, :no, :maybe
   Example hash-map:
     {:number 4123 :do? :yes}

   For each issue:
   * Assigns ms-curr milestone if :do? is :yes or :maybe
   * Assigns ms-next milestone if :do? is :no
   * Adds 'maybe' label if :do? is :maybe"
  [ms-curr ms-next plan]
  (let [{:keys [maybes edits] :as eds} (edits-from plan ms-curr ms-next)
        conn (github-conn)]
    (doseq [edit eds]
      (github/edit-issue conn edit))
    (doseq [number (map :number maybes)]
      (github/add-a-label conn number :maybe))
    eds))

(defn fetch-all-issues
  "Fetches and returns the union of issues assigned to the specified milestone
   and issues specified in plan"
  [ms-num plan]
  (let [conn (github-conn)
        msis (github/fetch-milestone-issues conn ms-num)
        setn  #(set (map :number %))
        mids (setn msis)
        pins (setn plan)
        mins (set/difference pins mids)
        adds (github/fetch-issues-by-nums conn mins)]
    (concat msis adds)))


(comment
  ;; Example of finalizing a plan.
  ;; (Updates current milestone and next, labels 'maybe', etc.)
  (jackalope.persist/import-plan "15.06.4.plan.tsv" "15.06.4.plan.edn" )
  (def PLAN (jackalope.persist/read-plan-from-edn "15.06.4.plan.edn"))
  (github! "conn-prod.edn")
  (def MS-CURR 174)
  (def MS-NEXT 175)
  ;;;(core/do-decisions MS-CURR MS-NEXT PLAN)
  )
