(ns jackalope.issues
  (:require [jackalope.github :as git]))

(defn closed? [issue]
  (= "closed" (:state issue)))

(def open?
  (complement closed?))

(defn has-label? [issue label]
  (some #(= (name label) (:name %)) (:labels issue)))

(defn has-maybe-label? [issue]
  (has-label? issue :maybe))

(defn open-maybe? [issue]
  (and (open? issue) (has-maybe-label? issue)))

(defn assignee [issue]
  (:login (:assignee issue)))

(defn creator [issue]
  (:login (:user issue)))

(defn login [issue]
  (get-in issue [:assignee :login]))
