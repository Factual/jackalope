(ns jackalope.zenhub-test
  (:require [clojure.test :refer :all]
            [jackalope.zenhub :refer :all]))

(def BOARDS-A
  {"pipelines"
   [{"name" "In Progress",
     "issues"
     [{"issue_number" 6046, "position" 0}
      {"issue_number" 5821, "position" 1}
      {"issue_number" 6496, "position" 2}
      {"issue_number" 6416, "estimate" {"value" 3}, "position" 3}
      {"issue_number" 6355, "estimate" {"value" 40}, "position" 4}]}]})

(deftest keep-in-boards-test []
  (let [inums [6046 6496 6355]
        res   (keep-in-boards inums BOARDS-A)
        res-p (first (res "pipelines"))
        inums-out (into #{} (map #(get % "issue_number") (res-p "issues")))]
    (is (= #{6046 6496 6355} inums-out))
    (is (= "In Progress" (res-p "name")))))
