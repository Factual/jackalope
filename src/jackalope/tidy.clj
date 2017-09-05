(ns jackalope.tidy
  (:require [jackalope.github :as github]
            [jackalope.issues :as issues]))

(defn linked-title [issue]
  (format "* [%s](%s)" (:title issue) (:html_url issue)))

(defn linked-titles [issues]
  (clojure.string/join "\n"
                       (map (fn [issue] (linked-title issue))
                            issues)))

(def REOPEN-STEPS
  (str
   "1. add a comment to explain why you'd like to keep it open\n"
   "2. specify an appropriate assignee\n"
   "3. re-open the issue\n"))

(def MSG-FOR-CLOSE
  (str "I'm closing this as stale. If you'd like to keep it open, please:\n"
       REOPEN-STEPS))

(defn msg-for-marker [assignee issues]
  (str
   "Hey @" assignee ",\n\n"
   "I'm planning to close the following issues as stale. "
   "Please review at your convenience. "
   "For any you'd like to keep open, please:\n"
   REOPEN-STEPS
   "\n__Stale Issues:__\n"
   (linked-titles issues)))

(defn close-as-stale! [conn issue]
  (github/comment-on-issue conn issue MSG-FOR-CLOSE)
  (github/add-a-label conn (:number issue) "icebox")
  (github/remove-milestone conn (:number issue))
  (github/close-issue conn issue))

(defn create-marker-issue
  "Creates a single issue assigned to assignee, with a listing of issues."
  [conn title assignee issues]
  (->> (github/create-issue conn
                            title
                            {:assignee assignee
                             :body (msg-for-marker assignee issues)})
       (github/close-issue conn)))

(defn close-all-as-stale!
  "Closes all issues as stale. For each issue:
   * adds label, 'icebox'
   * removes milestone
   * adds a comment explaining the closure
   * closes
   Groups issues by creator and for each creator, opens a new issue assigned to
   that creator with an explanation and listing of their closures."
  [conn issues]
  (doseq [[creator issues]
          (group-by issues/creator issues)]
    (create-marker-issue conn "Time to Sweep Olde Tickets" creator issues)
    (doseq [i issues]
      (close-as-stale! conn i))))

(comment
  (def STALE (:items (github/search-issues-stale CONN "2016-11-01")))
)
