(ns jackalope.zenhub
  (:require [org.httpkit.client :as http]
            [clojure.string :as str]
            [clojure.data.json :as json]))

;
; Wherever functions take a repo-id, it's the underlying github repo id, like:
; 1800123
; (it's *not* the human readable repo name)
;


(defn keep-nums [is inums]
  (filter 
   (fn [x]
     (contains? inums (x "issue_number")))
   is))

(defn prune [inums]
  (fn [pipe] (update-in pipe ["issues"] keep-nums inums)))

(defn keep-in-boards [inums boards]
  (let [inums (into #{} inums)]
    (assoc boards "pipelines" (map (prune inums) (boards "pipelines")))))

(defn get-boards
  "Returns ZenHub board data for the specified repository. 

   Returned hash-map is like:
     'pipelines'  [PIPELINES]

   PIPELINES is a collection of hash-maps, one for each pipeline in the board.
   Each pipeline hash-map is like:
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

(defn get-boards-keep [token repo-id issue-nums]
  (->> (get-boards token repo-id)
      (keep-in-boards issue-nums)))

(defn issue-nums->pipeline
  "Transforms ZenHub boards data into a lookup hash-map where keys are the issue
   numbers and values are the pipeline names, e.g.:
     {10282 'Nominated'
      8161 'In Review'
      9512 'Blocked'
      ...}"
  [boards]
  (apply assoc {}
         (mapcat #(interleave
                   (map (fn [i] (get i "issue_number")) (get % "issues"))
                   (repeat (get % "name")))
                 (get boards "pipelines"))))

(defn get-epics 
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

(defn get-epic-issue-nums
  "Returns the set of epic issue numbers for the specified repo."
  [token repo-id]
  (into #{} (map #(get % "issue_number")
                 (get (get-epics token repo-id) "epic_issues"))))

(defn +pipeline [n->p issue]
  (assoc issue :zenhub-pipeline (n->p (:number issue))))

(defn +epic? [ens issue]
  (assoc issue
         :zenhub-epic? (contains? ens (:number issue))))

(defn ++
  "Decorates each issue with ZenHub metadata. Adds key/value pairs to each 
   issue in issues:
     :zenhub-epic?     [true iff the issue is a ZenHub epic]
     :zenhub-pipeline  [name of associated pipeline, if any. E.g., 'Blocked']"
  [token repo-id issues]
  (let [boards (get-boards-keep token repo-id (map :number issues))
        n->p   (issue-nums->pipeline boards)
        ens    (get-epic-issue-nums token repo-id)]
    (map (comp (partial +pipeline n->p) (partial +epic? ens)) issues)))

(defn epic? [i]
  (:zenhub-epic? i))

(defn blocked? [i]
  (= "Blocked" (:zenhub-pipeline i)))

(defn maybe? [i]
  (= "Maybe" (:zenhub-pipeline i)))


;
; Conversions from ZenHub's format to ours
;

(defn as-int [v]
  (Integer/parseInt (str v)))

(defn groom-pipelines
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

(defn as-plan
  "Takes boards data and converts to a concise plan structure; returns a
   collection of hash-maps where each hash-map represents an issue and its
   decision in the plan.

   Example hash-map in the collection:
   {:number 6279, :do? :no}"
  [boards]
  (mapcat
   (fn [[p nums]]
    (for [n nums]
      {:number n
       :do? (case p
              :nominated :no
              :maybe :maybe
              :todo :yes
              :inprogress :yes
              :blocked :yes
              :inreview :yes
              :pending :yes
              :closed :yes  ;; TODO! this results in assigning the next milestone, even tho it's DONE
              :inscrutable)}))
   (groom-pipelines boards)))
