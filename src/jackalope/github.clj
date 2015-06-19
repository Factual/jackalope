;TODO! fail early and loudly if API error, e.g. bad CONN data like "somestr",
;      or failure to provide 2-factor auth OTP when turned on

(ns jackalope.github
  (:require [tentacles.issues :as issues]
            [tentacles.search :as search]
            [clojure.set :as set]))

(def ISSUE-KEY-BLACKLIST
  ^{:doc "Keys we use internally in issue recs but don't care to show to github"}
  [:label+])

(defn create-issue [{:keys [user repo auth]} title ms-num]
  (issues/create-issue user repo title (merge {:auth auth 
                                               :milestone ms-num})))

(defn for-issues
  "Grooms issue data i for sending to Github API.
   Perhaps not strictly necessary."
  [{:keys [user repo auth]} i]
  (apply dissoc (merge {:auth auth} i)
         ISSUE-KEY-BLACKLIST))

(defn pull-request? [i]
  (:pull_request i))

(defn fetch-issues-and-prs-by-milestone
  "Returns the issues assigned to the specified milestone"
  [{:keys [user repo auth]} ms-num]
  (issues/issues user repo 
                 {:auth      auth
                  :milestone ms-num
                  :state     "all"
                  :all-pages true}))

(defn fetch-issues-by-milestone
  "Returns the issues assigned to the specified milestone, minus pull requests."
  [auth ms-num]
  (remove pull-request?
          (fetch-issues-and-prs-by-milestone auth ms-num)))

(defn fetch-issues-by-nums [{:keys [user repo auth]} inums]
  (doall (map (fn [inum]
                (issues/specific-issue user repo inum {:auth auth}))
              inums)))

(defn edit-issue [{:keys [user repo auth] :as conn} edit]
  (let [e (for-issues conn edit)]
    (issues/edit-issue user repo (:number e) e)))

(defn add-a-label [{:keys [user repo auth]} inum label]
  (issues/add-labels user repo inum [label] {:auth auth}))

(defn remove-a-label
  "Removes the specified label from the specified issue.
   inum is the issue number.
   label is the label name, e.g. \"MyLabel\""
 [{:keys [user repo auth]} inum label]
 (issues/remove-label user repo inum (name label) {:auth auth}))

(defn fetch-issue-events [{:keys [user repo auth]} inum]
  (issues/issue-events user repo inum {:auth auth}))

;TODO! get this to work
; example that needs a working repo filter:
; (:total_count (search-issues CONN "geopulse" {}))
; also TODO: how to leave out keywords?
(defn search-issues [{:keys [user repo auth]} keywords q]
  (search/search-issues keywords q {:auth auth}))
