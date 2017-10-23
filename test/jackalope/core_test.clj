(ns jackalope.core-test
  (:require [clojure.test :refer :all]
            [jackalope.core :refer :all]))

(def PLAN1 [{:number 1 :do? :yes}])
(def PLAN2 [{:number 2 :do? :no}])
(def PLAN3 [{:number 3 :do? :maybe}])

(deftest actions-for-test-plan1 []
  (let [{:keys [edits maybes]} (actions-for PLAN1 :MS-CURR :MS-NEXT)
        expect {:number 1 :milestone :MS-CURR}]
    {:number 1 :milestone :MS-CURR}
    (is (= expect (first edits)))
    (is (empty? maybes))))

(deftest actions-for-test-plan2 []
  (let [{:keys [edits maybes]} (actions-for PLAN2 :MS-CURR :MS-NEXT)
        expect {:number 2 :milestone :MS-NEXT}]
    (is (= expect (first edits)))
    (is (empty? maybes))))

(deftest actions-for-test-plan3 []
  (let [{:keys [edits maybes]} (actions-for PLAN3 :MS-CURR :MS-NEXT)
        expect {:number 3 :milestone :MS-CURR :label+ :maybe}]
    (is (= expect (first edits)))
    (is (= expect (first maybes)))))


(comment
;; example issue retrieved from Github
{:labels
 [{:url "https://api.github.com/repos/Factual/front/labels/deployment",
   :name "deployment",
   :color "444444"}],
 :labels_url
 "https://api.github.com/repos/Factual/front/issues/4993/labels{/name}",
 :state "open",
 :locked false,
 :updated_at "2015-06-17T20:31:02Z",
 :closed_at nil,
 :html_url "https://github.com/Factual/front/issues/4993",
 :title "Add \"replacements\" section to deployment docs",
 :created_at "2015-06-17T20:31:02Z",
 :url "https://api.github.com/repos/Factual/front/issues/4993",
 :user
 {:following_url
  "https://api.github.com/users/heepster/following{/other_user}",
  :gists_url "https://api.github.com/users/heepster/gists{/gist_id}",
  :starred_url
  "https://api.github.com/users/heepster/starred{/owner}{/repo}",
  :followers_url "https://api.github.com/users/heepster/followers",
  :gravatar_id "",
  :avatar_url "https://avatars.githubusercontent.com/u/809169?v=3",
  :html_url "https://github.com/heepster",
  :received_events_url
  "https://api.github.com/users/heepster/received_events",
  :site_admin false,
  :login "heepster",
  :url "https://api.github.com/users/heepster",
  :organizations_url "https://api.github.com/users/heepster/orgs",
  :type "User",
  :events_url "https://api.github.com/users/heepster/events{/privacy}",
  :repos_url "https://api.github.com/users/heepster/repos",
  :id 809169,
  :subscriptions_url
  "https://api.github.com/users/heepster/subscriptions"},
 :comments 0,
 :events_url
 "https://api.github.com/repos/Factual/front/issues/4993/events",
 :number 4993,
 :body
 "https://github.com/Factual/front/issues/4991#issuecomment-112910172\r\n\r\n(Late add for 15.06.4)",
 :assignee nil,
 :id 89118479,
 :milestone
 {:creator
  {:following_url
   "https://api.github.com/users/dirtyvagabond/following{/other_user}",
   :gists_url
   "https://api.github.com/users/dirtyvagabond/gists{/gist_id}",
   :starred_url
   "https://api.github.com/users/dirtyvagabond/starred{/owner}{/repo}",
   :followers_url
   "https://api.github.com/users/dirtyvagabond/followers",
   :gravatar_id "",
   :avatar_url "https://avatars.githubusercontent.com/u/454908?v=3",
   :html_url "https://github.com/dirtyvagabond",
   :received_events_url
   "https://api.github.com/users/dirtyvagabond/received_events",
   :site_admin false,
   :login "dirtyvagabond",
   :url "https://api.github.com/users/dirtyvagabond",
   :organizations_url
   "https://api.github.com/users/dirtyvagabond/orgs",
   :type "User",
   :events_url
   "https://api.github.com/users/dirtyvagabond/events{/privacy}",
   :repos_url "https://api.github.com/users/dirtyvagabond/repos",
   :id 454908,
   :subscriptions_url
   "https://api.github.com/users/dirtyvagabond/subscriptions"},
  :labels_url
  "https://api.github.com/repos/Factual/front/milestones/174/labels",
  :state "open",
  :due_on "2015-06-19T07:00:00Z",
  :updated_at "2015-06-18T09:48:50Z",
  :closed_at nil,
  :html_url "https://github.com/Factual/front/milestones/15.06.4",
  :title "15.06.4",
  :created_at "2015-06-12T18:56:42Z",
  :closed_issues 25,
  :url "https://api.github.com/repos/Factual/front/milestones/174",
  :number 174,
  :open_issues 37,
  :id 1162573,
  :description ""},
 :comments_url
 "https://api.github.com/repos/Factual/front/issues/4993/comments"}

)

