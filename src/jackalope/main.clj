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
   ["-m" "--milestone MILESTONE"]
   ["-i" "--milestone-number MILESTONE-NUMBER"
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
  [{:keys [milestone milestone-number] :as opts}]
  (assert (or milestone milestone-number) "You must indicate a milestone")
  (merge opts
         (when milestone
           {:milestone-number
            (:number (core/get-open-milestone-number-for milestone))})))

(defn sweep!
  "Runs a sweep of the specified milestone into the next milestone.
   Assumes the next milestone id is milestone + 1.
   If preview is true, prints out actions but does not run them."
  [{:keys [milestone preview]}]
  (assert milestone "You must supply a --milestone when sweeping")
  (let [milestone-next (inc milestone)
        actions (core/sweep-milestone milestone milestone-next)]
    (if preview
      (doseq [a actions] (println a))
      (do
        (core/sweep! actions)
        (println (format "Swept %s issues into milestone %s" (count actions)
                         milestone-next))))))

(defn generate-retrospective-report
  "Assumes a local plan file name '[milestone title].plan.edn'"
  [{:keys [milestone]}]
  (core/generate-retrospective-report milestone))

(defn plan!
  "Finalizes the plan for the specified milestone.
   Assumes the next milestone id is milestone + 1.
   Assumes a local plan file name '[milestone title].plan.edn'"
  [{:keys [milestone preview]}]
  (let [plan (pst/import-plan-from-json milestone)]
    (if preview
      (let [{:keys [edits maybes]} (core/plan* plan milestone (inc milestone))]
        (doseq [e edits] (println e)))
      (do
        (core/plan! plan milestone (inc milestone))
        (println "Finalized plan for milestone" milestone)))))

(def COMMAND-FNS 
  {"sweep" sweep!
   "retrospective" generate-retrospective-report
   "plan" plan!})

(defn run [cmd opts]
  (let [conf (:conf opts)]
    (core/github! conf)
    (assert (contains? COMMAND-FNS cmd) "You must specify a valid action command")
    ((get COMMAND-FNS cmd) (good-opts opts))))

;; comments! and more

(defn -main
  [& args]
  (let [opts (parse-opts args cli-options)]
    (if-let [errs (:errors opts)]
      (print-stderr errs)
      (run (first args) (:options opts)))))

