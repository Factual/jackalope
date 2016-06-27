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
    :validate [#(good-conf-file? %) "Must be a valid configuration file"]]
   ["-m" "--milestone-title MILESTONE-TITLE"]
   ["-n" "--milestone-number MILESTONE-NUMBER"
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 65536) "Must be a valid milestone number"]]])

(defn print-stderr [errs]
  (binding [*out* *err*]
    (doseq [e errs]
      (println e))))

(defn good-opts
  "Validate and groom cli options:
   * make sure there's a valid milestone indicator
     (So far, all CLI commands require a milestone so this
      hardwires it as expected)"
  [{:keys [milestone-title milestone-number] :as opts}]
  (assert (or milestone-title milestone-number) "You must indicate a milestone")
  (merge opts
         (when (not milestone-title)
           {:milestone-title
            (:title (core/get-milestone milestone-number))})
         (when (not milestone-number)
           {:milestone-number
            (:number (core/get-open-milestone-by-title milestone-title))})))

(defn sweep!
  "Runs a sweep of the specified milestone into the next milestone.
   Assumes the next milestone number is milestone + 1.
   If preview is true, prints out actions but does not run them."
  [{:keys [milestone-number milestone-title preview]}]
  (assert milestone-number)
  (assert milestone-title)
  (let [milestone-next (inc milestone-number)
        actions (core/sweep-milestone milestone-title milestone-next)]
    (if preview
      (doseq [a actions] (println a))
      (do
        (core/sweep! actions)
        (println (format "Swept %s issues into milestone %s" (count actions)
                         milestone-next))))))

(defn generate-retrospective-report
  "Assumes a local plan file name '[milestone title].plan.edn'"
  [{:keys [milestone-title milestone-number]}]
  (core/generate-retrospective-report milestone-number milestone-title))

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

(def COMMAND-FNS 
  {"sweep" sweep!
   "retrospective" generate-retrospective-report
   "plan" plan!})

(defn run [cmd opts]
  (core/github! (:conf opts))
  (assert (contains? COMMAND-FNS cmd) "You must specify a valid action command")
  ((get COMMAND-FNS cmd) (good-opts opts)))

;; comments! and more

(defn -main
  [& args]
  (let [opts (parse-opts args cli-options)]
    (if-let [errs (:errors opts)]
      (print-stderr errs)
      (run (first args) (:options opts)))))

