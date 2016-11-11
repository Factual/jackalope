(ns jackalope.issues
  (:require [jackalope.github :as git]))

(defn closed? [i]
  (= "closed" (:state i)))

(def open?
  (complement closed?))

(defn has-label? [i l]
  (some #(= (name l) (:name %)) (:labels i)))

(defn has-maybe-label? [i]
  (has-label? i :maybe))

(defn open-maybe? [i]
  (and (open? i) (has-maybe-label? i)))
