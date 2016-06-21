;TODO! fail early and loudly if API error, e.g. bad CONN data like "somestr",
;      or failure to provide 2-factor auth OTP when turned on

(ns jackalope.github
  (:require [tentacles.issues :as issues]
            [tentacles.search :as search]
            [clojure.set :as set]))

(def ISSUE-KEY-BLACKLIST
  ^{:doc "Keys we use internally in issue recs but don't care to show to github"}
  [:label+])

(defmacro assure
  "Runs body, presumably a github call. Examines the result for metadata.
   If there's metadata, assumes the request was good and returns it.
   Else, throws an exception with a (hopefully) useful message."
  {:added "1.0"}
  [& body]
  `(let [res# ~@body]
     (if (meta res#)
        res#
        (throw (RuntimeException. (format "Bad result, status: %s. %s"
                                          (:status res#)
                                          (get-in res# [:body :message])))))))

(defn create-issue [{:keys [user repo auth]} title ms-num]
  (assure (issues/create-issue user repo title (merge {:auth auth 
                                                       :milestone ms-num}))))

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
  ;; TODO: not wrapping in (assure...), since it seems that sometimes this 
  ;;       naturally returns nil metadata (??). Need to find a good way to
  ;;       to assure the result is good
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
                (assure (issues/specific-issue user repo inum {:auth auth})))
              inums)))

(defn edit-issue [{:keys [user repo auth] :as conn} edit]
  (let [e (for-issues conn edit)]
    (assure (issues/edit-issue user repo (:number e) e))))

(defn add-a-label [{:keys [user repo auth]} inum label]
  (assure (issues/add-labels user repo inum [label] {:auth auth})))

(defn remove-a-label
  "Removes the specified label from the specified issue.
   inum is the issue number.
   label is the label name, e.g. \"MyLabel\""
 [{:keys [user repo auth]} inum label]
  (assure (issues/remove-label user repo inum (name label) {:auth auth})))

(defn fetch-issue-events [{:keys [user repo auth]} inum]
  (assure (issues/issue-events user repo inum {:auth auth})))

;TODO! get this to work
; example that needs a working repo filter:
; (:total_count (search-issues CONN "geopulse" {}))
; also TODO: how to leave out keywords?
(defn search-issues [{:keys [user repo auth]} keywords q]
  (assure (search/search-issues keywords q {:auth auth})))
