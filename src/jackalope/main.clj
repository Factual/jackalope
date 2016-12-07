;
; Supports the command line interface. 
; 
;
; Commands:
;
; plan  For use after setting a plan. Retrieves boards from ZenHub and updates
;       tickets per our decisions. 
;   requires --conf
;   requires a --milestone-title or a --milestone-number
;   supports --preview
;
; sweep  For use at the end of a sprint. Sweeps tickets from one milestone to
;        the next.
;   requires --conf
;   requires a --milestone-title or a --milestone-number
;   supports --preview
;
; retrospective  For use at the end of a sprint. Creates a simple HTML file with
;                sprint outcomes.
;   requires --conf
;   requires a --milestone-title or a --milestone-number
;   output will be a an HTML file named after the sprint's milestone, e.g.:
;     16.11.2.retrospective.html
;
; Example CLI calls, using lein:
;   lein run -- plan --conf github-prod.edn -n 225 --preview
;   lein run -- plan --conf github-prod.edn -n 225
;   lein run -- sweep --conf github-prod.edn -n 225
;   lein run -- retrospective --conf github-prod.edn -n 225
;
(ns jackalope.main
  (:require [jackalope.core :as core]
            [jackalope.persist :as pst]
            [clojure.tools.cli :refer [parse-opts]])
  (:gen-class))

(defn good-conf-file? [f]
  (try
    (read-string (slurp f))
    (catch Exception _ false)))

(def cli-options
  [["-p" "--preview" "Show what would happen but don't actually do it"]
   ["-c" "--conf CONNF" "Configuration file (github auth)"
    :default core/DEFAULT-CONF
    ;; side effect; caches github auth
    :parse-fn #(core/github! %)]
   ["-m" "--milestone-title MILESTONE-TITLE"]
   ["-n" "--milestone-number MILESTONE-NUMBER"
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 65536) "Must be a valid milestone number"]]
   ["-h" "--help" "Show this help message"]])

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn usage [options-summary]
  (->> ["This is the command line interface for Jackalope, an opinionated tool for Github tikcet management."
        ""
        "Usage: program-name [options] action"
        ""
        "Options:"
        options-summary
        ""
        "Actions:"
        "  plan            Finalize a plan"
        "  sweep           Sweep a closing milestone"
        "  retrospective   Create a retrospective report"
        ""
        "Please refer to the manual page for more information."]
       (clojure.string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (clojure.string/join \newline errors)))

(defn good-opts
  "Validate and groom cli options:
   * make sure there's a valid milestone indicator
     (So far, all CLI commands require a milestone so this
      hardwires it as expected)"
  [{:keys [milestone-title milestone-number] :as opts}]
  (assert (or milestone-title milestone-number) "You must indicate a milestone")
  (merge opts
         (when (not milestone-title)
           ;;TODO: we don't always need this. i.e. and e.g., sweep! does not require a milestone title
           {:milestone-title
            (:title (core/get-milestone milestone-number))})
         (when (not milestone-number)
           {:milestone-number
            (:number (core/get-open-milestone-by-title milestone-title))})))

(defn sweep!
  "Runs a sweep of the specified milestone into the next milestone.
   Assumes the next milestone number is milestone + 1.
   If preview is true, prints out actions but does not run them."
  [{:keys [milestone-number preview]}]
  (assert milestone-number)
  (let [milestone-next (inc milestone-number)
        actions (core/sweep-milestone milestone-number milestone-next)]
    (if preview
      (do
        (println "Sweep preview, " (count actions) " actions:")
        (doseq [a actions] (println a)))
      (do
        (core/sweep! actions)
        (println (format "Swept %s issues into milestone %s" (count actions)
                         milestone-next))))))

(defn generate-retrospective-report
  "Assumes a local plan file name '[milestone title].plan.edn'"
  [{:keys [milestone-title milestone-number]}]
  (let [f (core/generate-retrospective-report milestone-number milestone-title)]
    (println "Saved retrospective report at" f)))

(defn plan!
  "Imports the specified plan from ZenHub and finalizes it in Github.

   Plan files will be saved locally, one as JSON (the data imported from ZenHub)
   and one as EDN (the Jackalope-ready format).

   Assumes the next milestone number is milestone + 1.

   If preview is true, writes the plan files and outputs the edits that would
   happen, but does not run the edits."
  [{:keys [milestone-title milestone-number preview]}]
  (assert milestone-number)
  (assert milestone-title)
  (let [{:keys [plan]} (core/import-plan-from-zenhub
                        milestone-number
                        milestone-title)]
    (if preview
      (let [{:keys [edits maybes]}
            (core/plan* plan milestone-number (inc milestone-number))]
        (doseq [e edits] (println e)))
      (do
        (core/plan! plan milestone-number (inc milestone-number))
        (println (format "Finalized plan for %s (%s)" milestone-title milestone-number))))))

(comment
  ;; Example call to plan!, preview mode:
  (plan! {:preview true
           :milestone-number 219
           :milestone-title "16.09.1"})

  ;; Example call to plan!, really do it:
  (plan! {:milestone-number 219
          :milestone-title "16.09.1"}))


(def COMMAND-FNS 
  {"sweep" sweep!
   "retrospective" generate-retrospective-report
   "plan" plan!})

(defn run [cmd opts]
  (assert (contains? COMMAND-FNS cmd) "You must specify a valid action command")
  (core/github! (:conf opts)) ;;TODO! why wasn't this handled while parsing? should refactor that for reliability/clarity!!
  ((get COMMAND-FNS cmd) (good-opts opts)))

(defn -main
  [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond 
      (not= (count arguments) 1) (exit 1 (usage summary))
      (:help options) (exit 0 (usage summary))
      errors (exit 1 (error-msg errors)))
    (run (first args) options)))
