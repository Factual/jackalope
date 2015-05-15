(defproject jackalope "0.0.1"
  :description "Github integration service to support custom release planning processes"
  :url "https://github.com/Factual/jackalope"
  :scm {:name "git"
        :url "https://github.com/Factual/jackalope"}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :deploy-repositories [["clojars" {:creds :gpg}]]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [tentacles "0.3.0"]
                 [hiccup "1.0.5"]])
