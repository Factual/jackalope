(ns jackalope.github
  (:require [tentacles.issues :as issues]
            [tentacles.search :as search]
            [tentacles.repos :as repos]
            [clojure.set :as set]))

;TODO: public functions in this namespace make inconsistent use of how to
;      specify an issue (sometimes a hash-map, sometimes the issue number)

(def ISSUE-KEY-BLACKLIST
  ^{:doc "Keys we use internally in issue recs but don't care to show to github"}
  [:label+])

(defmacro assure
  "Runs body, presumably a github call. Examines the result for metadata.
   If there's metadata, assumes the request was good and returns it.
   Else, throws an exception with a (hopefully) useful message."
  ;;TODO: it's a poor assumption that meta=success. 
  ;;      E.g., valid search API calls don't return meta,
  ;;      so for now we don't use assure.
  ;;TODO: allow all error info to bubble up.
  ;;      E.g., use slingshot and include access to full API resp
  {:added "1.0"}
  [& body]
  `(let [res# ~@body]
     (if (meta res#)
        res#
        (throw (RuntimeException. (format "Bad result, status: %s. %s"
                                          (:status res#)
                                          (get-in res# [:body :message])))))))

(defn create-issue
  "Creates a new issue with the specified title.

   Possible option keys include:
     :milestone :assignee :labels :body

   Example call:
     (create-issues CONN 'My Title' {:milestone 227})"
  [{:keys [user repo github-token]} title options]
  (assure (issues/create-issue user repo title
                               (merge {:oauth-token github-token}
                                      options))))

(defn comment-on-issue [{:keys [user repo github-token]} issue body]
  (assure (issues/create-comment user repo (:number issue) body
                                 {:oauth-token github-token})))

(defn groom-for-github
  "Grooms issue data for sending to Github API.
   Perhaps not strictly necessary."
  [{:keys [user repo github-token]} issue]
  (apply dissoc (merge {:oauth-token github-token} issue)
         ISSUE-KEY-BLACKLIST))

(defn pull-request? [i]
  (:pull_request i))

(defn fetch-issues-and-prs-by-milestone
  "Returns the issues assigned to the specified milestone"
  [{:keys [user repo github-token]} ms-num]
  (let [is (issues/issues user repo 
                          {:oauth-token github-token
                           :milestone ms-num
                           :state     "all"
                           :all-pages true})]
    ;; for some reason, this doesn't always return non-nil metadata, therefore
    ;; not using (assure). Instead, assuming that a non map structure as the
    ;; top-level results means that something is wrong with getting issues.
    (if-not (map? is)
      is
      (throw (RuntimeException. (format "Bad result, status: %s. %s"
                                        (:status is)
                                        (str (get-in is [:body :message]) " "
                                             (get-in is [:body :errors]))))))))

(defn fetch-issues-by-milestone
  "Returns the issues assigned to the specified milestone, minus pull requests."
  [conn ms-num]
  (remove pull-request?
          (fetch-issues-and-prs-by-milestone conn ms-num)))

(defn fetch-closed-issues-by-milestone
  "Returns the issues assigned to the specified milestone"
  [{:keys [user repo github-token]} ms-num]
  (let [is (issues/issues user repo
                          {:oauth-token github-token
                           :milestone ms-num
                           :state     "closed"
                           :all-pages true})]
    ;; for some reason, this doesn't always return non-nil metadata, therefore
    ;; not using (assure). Instead, assuming that a non map structure as the
    ;; top-level results means that something is wrong with getting issues.
    (if-not (map? is)
      (remove pull-request? is)
      (throw (RuntimeException. (format "Bad result, status: %s. %s"
                                        (:status is)
                                        (str (get-in is [:body :message]) " "
                                             (get-in is [:body :errors]))))))))

(defn fetch-issue [{:keys [user repo github-token]} inum]
  (assure (issues/specific-issue user repo inum {:oauth-token github-token})))

(defn fetch-issues-by-nums [{:keys [user repo github-token]} inums]
  (doall (map (fn [inum]
                (assure (issues/specific-issue user repo inum {:oauth-token github-token})))
              inums)))

(defn edit-issue [{:keys [user repo] :as conn} edit]
  (let [e (groom-for-github conn edit)]
    (assure (issues/edit-issue user repo (:number e) e))))

(defn close-issue [conn {:keys [number]}]
  (edit-issue conn {:number number :state "closed"}))

(defn add-a-label [{:keys [user repo github-token]} inum label]
  (assure (issues/add-labels user repo inum [label] {:oauth-token github-token})))

(defn remove-a-label
  "Removes the specified label from the specified issue.
   inum is the issue number.
   label is the label name, e.g. \"MyLabel\""
 [{:keys [user repo github-token]} inum label]
  (assure (issues/remove-label user repo inum (name label) {:oauth-token github-token})))

(defn fetch-issue-events [{:keys [user repo github-token]} inum]
  (assure (issues/issue-events user repo inum {:oauth-token github-token})))

;; TODO: be sure to fetch *all* milestones and filter them
(defn fetch-open-milestone-by-title [{:keys [user repo github-token]} title]
  (let [mss (issues/repo-milestones user repo {:oauth-token github-token :state :open})
        ms (first (filter #(= title (:title %)) mss))]
    (assert ms (str "Did not find an open milestone with title: " title))
    ms))

(defn remove-milestone [conn inum]
  (edit-issue conn {:number inum
                             :milestone nil}))

(defn fetch-repo [{:keys [user repo github-token]}]
  ;; doesn't seem to require a repo name; always returns the data for repo
  (assure (repos/specific-repo user repo {:oauth-token github-token})))

(defn fetch-comments [{:keys [user repo github-token]} issue]
  (assure (issues/issue-comments user repo (:number issue) {:oauth-token github-token})))

;TODO: it appears that paging of search results does not work properly. 
;      if you specify :all-pages=true and there are multiple pages of results,
;      the results compe back as a LazySeq rather than a hash-map and it's all
;      downhill from there. Workaround: for now, we're using :per-page instead

(defn search-issues-assigned
  [{:keys [user repo github-token]} assignee title-search]
  (assure (search/search-issues title-search
                                {:repo (str user "/" repo)
                                 :type "issue"
                                 :in "title"
                                 :state "open"
                                 :assignee assignee}
                                {:oauth-token github-token :per-page 100})))

(defn search-issues-stale
  "Returns all open issues in repo that were created before the specified time.
   created-before-str should be a time formatted per GitHub chosen standard,
   ISO8601, e.g.: '2017-12-31' for December 31st, 2017"
  [{:keys [user repo github-token]} created-before-str]
  ;; TODO: trying to sort by created/asc breaks the results in a weird way;
  ;;       no meta, and results set is tiny
  (search/search-issues ""
                                {:repo (str user "/" repo)
                                 :type "issue"
                                 :created (str "<" created-before-str)
                                 ;;:sort "created"
                                 ;;:order "asc"
                                 :state "open"}
                                {:oauth-token github-token :per-page 100}))

(defn fetch-open-issues
  [{:keys [user repo github-token]}]
  (let [is (issues/issues user repo
                          {:oauth-token github-token
                           :state     "open"
                           :all-pages true})]
    ;; for some reason, this doesn't always return non-nil metadata, therefore
    ;; not using (assure). Instead, assuming that a non map structure as the
    ;; top-level results means that something is wrong with getting issues.
    ;; TODO: update: probably due to paging bugginess... if results have multiple pages
    ;;       that could be badness
    ;; TODO: the following ugly code is duplicative (see above)
    (if-not (map? is)
      (remove pull-request? is)
      (throw (RuntimeException. (format "Bad result, status: %s. %s"
                                        (:status is)
                                        (str (get-in is [:body :message]) " "
                                             (get-in is [:body :errors]))))))))

(defn fetch-milestone [{:keys [user repo github-token]} ms-num]
  (assure (issues/specific-milestone user repo ms-num {:oauth-token github-token})))

(defn set-milestone-desc [{:keys [user repo github-token]} ms-num ms-title desc]
  (assure (issues/edit-milestone user repo ms-num ms-title {:oauth-token github-token
                                                            :description desc})))
