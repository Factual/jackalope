(defproject jackalope "0.0.2"
  :description "An opinionated approach to spry software development using github."
  :url "https://github.com/Factual/jackalope"
  :scm {:name "git"
        :url "https://github.com/Factual/jackalope"}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :deploy-repositories [["clojars" {:creds :gpg}]]
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [tentacles "0.3.0"]
                 [hiccup "1.0.5"]
                 [clojure-csv "2.0.1"]
                 [org.clojure/data.json "0.2.6"]])
