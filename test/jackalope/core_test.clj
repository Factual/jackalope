(ns jackalope.core-test
  (:require [clojure.test :refer :all]
            [jackalope.core :refer :all]))

(def PLAN1 [{:number 1 :do? :yes}])
(def PLAN2 [{:number 2 :do? :no}])
(def PLAN3 [{:number 3 :do? :maybe}])

(deftest edits-from-test-plan1 []
  (let [{:keys [edits maybes]} (edits-from PLAN1 :MS-CURR :MS-NEXT)
        expect {:number 1 :milestone :MS-CURR}]
    {:number 1 :milestone :MS-CURR}
    (is (= expect (first edits)))
    (is (empty? maybes))))

(deftest edits-from-test-plan2 []
  (let [{:keys [edits maybes]} (edits-from PLAN2 :MS-CURR :MS-NEXT)
        expect {:number 2 :milestone :MS-NEXT}]
    (is (= expect (first edits)))
    (is (empty? maybes))))

(deftest edits-from-test-plan3 []
  (let [{:keys [edits maybes]} (edits-from PLAN3 :MS-CURR :MS-NEXT)
        expect {:number 3 :milestone :MS-CURR :label+ :maybe}]
    (is (= expect (first edits)))
    (is (= expect (first maybes)))))
