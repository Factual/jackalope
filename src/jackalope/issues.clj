(ns jackalope.issues
  (:require [jackalope.github :as git]))

(defn closed? [i]
  (= "closed" (:state i)))

(def open?
  (complement closed?))

(defn has-maybe-label? [i]
  (some #(= "maybe" (:name %)) (:labels i)))

(defn open-maybe? [i]
  (and (open? i) (has-maybe-label? i)))
