;
; Supports the command line interface. 
; 
;
; Commands:
;
; Example CLI calls, using lein:
;
(ns jackalope.main
  (:require [jackalope.core :as core]
            [clojure.tools.cli :refer [parse-opts]])
  (:gen-class))

(defn good-conf-file? [f]
  (try
    (read-string (slurp f))
    (catch Exception _ false)))

(def cli-options
  [["-p" "--preview" "Show what would happen but don't actually do it"]
   ["-c" "--conf CONF" "Configuration file (github auth)"
    :default core/DEFAULT-CONF
    ;; side effect; caches github auth
    :parse-fn #(core/github! %)]
   ["-a" "--assignee ASSIGNEE"]
   ["-h" "--help" "Show this help message"]])

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn usage [options-summary]
  (->> ["This is the command line interface for Jackalope, an opinionated tool for Github ticket management."
        ""
        "Usage: program-name [options] action"
        ""
        "Options:"
        options-summary
        ""
        "Actions:"
        "  check-sprint    Perform a check for sprint related work; do the work"
        "  loop            Run the main work loop, forever"
        ""
        "Please refer to the manual page for more information."]
       (clojure.string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (clojure.string/join \newline errors)))

(defn check-sprint [{:keys [assignee]}]
  (core/check-sprint assignee))

(defn watch [{:keys [assignee] :as opts}]
  (println "watching...")
  (check-sprint opts)
  (Thread/sleep 5000)
  (recur opts))

(def COMMAND-FNS 
  {"check-sprint" check-sprint
   "watch" watch})

(defn run [cmd opts]
  (assert (contains? COMMAND-FNS cmd) "You must specify a valid action command")
  ((get COMMAND-FNS cmd) opts))

(defn -main
  [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond 
      (not= (count arguments) 1) (exit 1 (usage summary))
      (:help options) (exit 0 (usage summary))
      errors (exit 1 (error-msg errors)))
    (run (first args) options)))
