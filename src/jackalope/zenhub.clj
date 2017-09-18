(ns jackalope.zenhub
  (:require [org.httpkit.client :as http]
            [clojure.string :as str]
            [clojure.data.json :as json]))

;
; Wherever functions take a repo-id, it's the underlying github repo id, like:
; 1800123
; (it's *not* the human readable repo name)
;


(defn keep-nums [issues inums]
  (filter 
   (fn [x]
     (contains? inums (x "issue_number")))
   issues))

(defn prune [inums]
  (fn [pipe] ))

(defn keep-in-pipeline [inums p]
  (update-in p ["issues"] keep-nums inums))

(defn fetch-boards
  "Returns ZenHub board data for the specified repository. 

   Returned hash-map is like:
     'pipelines'  [PIPELINES]

   PIPELINES is a collection of hash-maps, one for each pipeline in the board.
   Each pipeline hash-map is like:
     'id'     [ID, e.g., '55cfb4726acc911c6bb064f1']
     'name'   [NAME, e.g. 'To Do']
     'issues' [ISSUES]

   ISSUES is a collection of hash-maps, one for each github issue. Each issue
   hash-map is like:
     'issue_number'  [ISSUE_NUMBER]
     'estimate'      [ESTIMATE(hash-map)]
     'position'      [POSITION_NDX]"
  [token repo-id]
  (let [url (format "https://api.zenhub.io/p1/repositories/%s/board" repo-id)
        {:keys [status headers body error] :as resp}
        @(http/get url {:headers {"X-Authentication-Token" token}})]
    (if-not error
      ;;TODO: validate that we have a useful response? e.g., maybe (assert (= status :ready)) 
      (json/read-str body)
      ;;else, stop execution. i'm not sure of format for error string from 
      ;;  Zenhub... punting by just str'ing it out
      ;;TODO: throw something meaningful. e.g., this can happen if bad token
      (throw (RuntimeException. (str error))))))

(defn fetch-pipelines
  "issue-nums must be a collection of issue numbers as numbers"
  ([token repo-id]
   (-> (fetch-boards token repo-id)
       (get "pipelines")))
  ([token repo-id issue-nums]
   (map #(keep-in-pipeline (into #{} issue-nums) %)
        (fetch-pipelines token repo-id))))

(defn is->m [pipeline]
  (let [name (get pipeline "name")]
    (reduce 
     (fn [m {:strs [issue_number estimate] :as i}]
       (assoc m issue_number (merge  {:pipeline name}
                                     (when-let [e (get estimate "value")]
                                       {:estimate e}))))
     {}
     (get pipeline "issues"))))

(defn issue-nums->zhdata
  "Transforms ZenHub boards data into a lookup hash-map where keys are the issue
   numbers and values are a hash-map representing ZenHub data. E.g.:
     {10101 {:board-name 'Maybe', :estimate nil},
      10100 {:board-name 'Maybe', :estimate 8},
      10102 {:board-name 'To Do', :estimate 5},
      10328 {:board-name 'To Do', :estimate 2}}"
  [pipelines]

  (apply merge (map is->m pipelines)))

(defn fetch-epics 
  "Returns metadata for ZenHub epics in the specified repository.

   Returns a collection of hash-maps where each hash-map represents an epic and
   looks like:
     'issue_number'  [ISSUE_NUMBER]
     'repo_id'       [REPO_ID]
     'issue_url'     [ISSUE_URL]"
  [token repo-id]
  (let [url (format "https://api.zenhub.io/p1/repositories/%s/epics" repo-id)
        {:keys [status headers body error] :as resp}
        @(http/get url {:headers {"X-Authentication-Token" token}})]
    (if-not error
      ;;TODO: validate that we have a useful response? e.g., maybe (assert (= status :ready)) 
      (json/read-str body)
      ;;else, stop execution. i'm not sure of format for error string from 
      ;;  Zenhub... punting by just str'ing it out
      ;;TODO: throw something meaningful. e.g., this can happen if bad token
      (throw (RuntimeException. (str error))))))

(defn fetch-epic-issue-nums
  "Returns the set of epic issue numbers for the specified repo."
  [token repo-id]
  (into #{} (map #(get % "issue_number")
                 (get (fetch-epics token repo-id) "epic_issues"))))

(defn- _++ [issues pipelines ens]
  (let [n->zh (issue-nums->zhdata pipelines)]
    (map
     (fn [{:keys [number] :as i}]
       (-> i
           (merge (get n->zh number))
           (assoc :epic? (contains? ens number))))
     issues)))

;TODO: can do as just one call, since pipes have 'is_epic'?
;      (include a higher level refactor?)
;TODO: can be more complete & useful, by including pipeline name(?)
(defn ++ [token repo-id issues]
  (_++ issues
       (let [issue-nums (map :number issues)] 
         (fetch-pipelines token repo-id issue-nums))
       (fetch-epic-issue-nums token repo-id)))

(defn epic? [i]
  (:epic? i))

(defn blocked? [i]
  (= "Blocked" (:pipeline i)))

(defn maybe? [i]
  (= "Maybe" (:pipeline i)))


;
; Conversions from ZenHub's format to ours
;



#_(defn groom-pipelines
  "Reads in the raw JSON exported from ZenHub. Expects ZenHub's format.

   Returns a hash-map where keys are the pipelines and values are a collection of issue 
   numbers, representing the issues in that pipeline. Like:
     :nominated  [numbers for the issues still nominated, so those are a 'no']
     :maybe      [numbers for the issues identified as Maybes]
     :todo       [numbers for the issues selected as yes]
     :inprogress [numbers for the issues already being worked on]
     :pending    [numbers for the issues being worked on but maybe blocked]
     :closed     [numbers for the issues that are already done]"
  [j]
  (into {}
        (for [p (get j "pipelines")]
          [(-> (get p "name") 
               (str/replace #"\s" "")
               str/lower-case
               keyword)
           (map #(as-int (get % "issue_number"))
                (get p "issues"))])))

