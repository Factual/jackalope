;TODO! fail early and loudly if API error, e.g. bad CONN data like "somestr"

(ns jackalope.github
  (:require [tentacles.issues :as issues]
            [clojure.set :as set]))

(def ISSUE-KEY-BLACKLIST
  ^{:doc "Keys we use internally in issue recs but don't care to show to github"}
  [:label+])

(defn for-issues [{:keys [user repo auth]} i]
  (apply dissoc (merge {:auth auth} i)
         ISSUE-KEY-BLACKLIST))

(defn fetch-milestone-issues
  "Returns lazy sequence of issues assigned to the specified milestone"
  [{:keys [user repo auth]} milestone-id]
  (filter #(nil? (:pull_request %)) ;; remove PRs, no want
          (issues/issues user repo 
                         {:auth auth
                          :milestone milestone-id
                          :state "all"
                          :all-pages true})))

(defn fetch-issues-by-nums [{:keys [user repo auth]} inums]
  (doall (map (fn [inum]
                (issues/specific-issue user repo inum {:auth auth}))
              inums)))

(defn edit-issue [{:keys [user repo auth] :as conn} edit]
  (let [e (for-issues edit)]
    (println "PERSIST:" e)
    (issues/edit-issue user repo (:number e) e)))

(defn add-a-label [{:keys [user repo auth]} inum label]
  (println "ADD-A-LABEL:" inum label)
  (issues/add-labels user repo inum [label] (merge {:auth auth})))
