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
            [jackalope.retrospective :as retro]
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
    :parse-fn #(core/connect! %)]
   ["-a" "--assignee ASSIGNEE"]
   ["-m" "--milestone MILESTONE"]  ;; TODO: use parser library to inforce integer
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
        "  start-sprint    Perform a sprint start for the specified milestone"
        "  stop-sprint    Perform a sprint stop for the specified milestone"
        "  check-sprint    Perform a check for sprint related work; do the work"
        ""
        "Please refer to the manual page for more information."]
       (clojure.string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (clojure.string/join \newline errors)))

(defn check-sprint [{:keys [assignee preview]}]
  (assert assignee "You must specify an assignee with -a or --assignee")
  (core/check-sprint assignee preview))

(defn preview-sprint-start [{:keys [ms-num ms-title plan-tbl]}]
  (println "------ Sprint start preview ------")
  (println (format "Milestone #%s, '%s'" ms-num ms-title))
  (println plan-tbl))

;TODO: duplicative of code in retro namespace. DRY it up.
(defn print-outcomes [retro]
  (doseq [[outcome issues] retro] 
    (println "---" (name outcome) "---")
    (doseq [{:keys [number owner title]} (sort-by :owner issues)]
      (println number owner title))))

(defn preview-sprint-stop [{:keys [actions plan issues]}]
  (println "------ Sprint stop preview ------")
  (let [retro (retro/retrospective plan issues)
        ris (retro/as-issues retro)
        shouts (retro/shout-outs ris)]
    (println "Outcomes:")
    (print-outcomes retro)
    (println "Shout outs:")
    (clojure.pprint/pprint shouts)
    (println "Actions:")
    (doseq [a actions]
      (println a))))

(defn start-sprint
  "Performs all actions to start a sprint, including:
   * Creates a new issue to represent the sprint start
   * Reads the sprint plan from ZenHub boards
   * Sweeps tickets based on the plan"
  [{:keys [preview milestone]}]
  (assert milestone
          "You must specify a valid milestone number with -m or --milestone")
  (let [ss (core/sprint-start** milestone)]
    (if preview
      (preview-sprint-start ss)
      (do
        (core/do-sprint-start! ss)
        (println "Done.")))))

(defn stop-sprint
  "Performs all actions to stop a sprint, including:
   * Creates a new issue to represent the sprint stop
   * Reads the sprint plan from the milestone description
   * Generates a sprint report and includes in the issue
   * Sweeps undone tickets to the next milestone"
  [{:keys [preview milestone]}]
  (assert milestone
          "You must specify a valid milestone number with -m or --milestone")
  (let [ms-curr (Integer/parseInt milestone) ;;TODO: rely on CLI parser instead for integer
        ss (core/sprint-stop** ms-curr)]
    (if preview
      (preview-sprint-stop ss)
      (do
        (core/do-sprint-stop! ss)
        (println "Done.")))))

(def COMMAND-FNS 
  {"check-sprint" check-sprint
   "start-sprint" start-sprint
   "stop-sprint" stop-sprint})

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
