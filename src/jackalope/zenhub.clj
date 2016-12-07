(ns jackalope.zenhub
  (:require [org.httpkit.client :as http]
            [clojure.data.json :as json]))

(defn- get-pipelines-
  [token repo-id]
  @(http/get (format "https://api.zenhub.io/p1/repositories/%s/board" repo-id)
             {:headers {"X-Authentication-Token" token}}))

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
  "Returns ZenHub board data for the specified repository. Returned hash-map is
   like:
     'pipelines'  [PIPELINES]

   PIPELINES is a collection of hash-maps, one for each pipeline in the board.
   Each pipepline hash-map is like:
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
