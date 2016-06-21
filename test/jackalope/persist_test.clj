(ns jackalope.persist-test
  (:require [clojure.test :refer :all]
            [jackalope.persist :refer :all]))

(deftest normalize-decision-text-test []
  (is (= :yes (normalize-decision-text "yes")))
  (is (= :yes (normalize-decision-text " YES! ")))
  (is (= :yes (normalize-decision-text "y")))

  (is (= :yes (normalize-decision-text "done")))
  (is (= :yes (normalize-decision-text " DONE! ")))
  (is (= :yes (normalize-decision-text "d")))

  (is (= :no (normalize-decision-text "no")))
  (is (= :no (normalize-decision-text " NO! ")))
  (is (= :no (normalize-decision-text "n")))

  (is (= :maybe (normalize-decision-text "maybe")))
  (is (= :maybe (normalize-decision-text " MAYBE! ")))
  (is (= :maybe (normalize-decision-text "m")))

  (is (= :inscrutable (normalize-decision-text nil)))
  (is (= :inscrutable (normalize-decision-text "")))
  (is (= :inscrutable (normalize-decision-text "      ")))
  (is (= :inscrutable (normalize-decision-text "unicorn")))
  (is (= :inscrutable (normalize-decision-text :inscrutable)))

  (is (= :yes (normalize-decision-text :yes)))
  (is (= :no (normalize-decision-text :NO)))
  (is (= :maybe (normalize-decision-text :May)))
  )
