(ns jackalope.zenhub-test
  (:require [clojure.test :refer :all]
            [jackalope.zenhub :refer :all]))

(def NOMINATED
  {"name" "Nominated",
   "issues"
   [{"issue_number" 1, "position" 0}
    {"issue_number" 2, "position" 1}
    {"issue_number" 3, "position" 2}]})

(def MAYBES
  {"name" "To Do",
   "issues"
   [{"issue_number" 4, "position" 0}
    {"issue_number" 5, "position" 1}
    {"issue_number" 6, "position" 2}]})

(def INPROGRESS
  {"name" "In Progress",
   "issues"
   [{"issue_number" 6046, "position" 0}
    {"issue_number" 5821, "position" 1}
    {"issue_number" 6496, "position" 2}
    {"issue_number" 6416, "estimate" {"value" 3}, "position" 3}
    {"issue_number" 6355, "estimate" {"value" 40}, "position" 4}]})

(def BOARDS
  {"pipelines"
   [NOMINATED MAYBES INPROGRESS]})

(defn gisns [board]
  (map #(get % "issue_number") (board "issues")))

(deftest keep-in-boards-test []
  (let [inums [6046 6496 6355 1 2 3 5]
        res   (keep-in-boards inums BOARDS)
        [nom may inp] (res "pipelines")
        inums-out (into #{} (mapcat gisns [nom may inp]))]
    (is (= #{1 2 3 5 6046 6496 6355} inums-out))
    (is (= "Nominated" (nom "name")))
    (is (= "To Do" (may "name")))
    (is (= "In Progress" (inp "name")))))
