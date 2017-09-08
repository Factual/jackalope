(ns jackalope.markdown
  (:require [jackalope.issues :as is]
            [clojure.string :as str]))

(defn table-head [heads]
  (str
   (str/join "|" heads) "\n"
   (str/join "|" (repeat (count heads) "---"))))

(defn table-rows [rows]
  (str/join "\n"
            (map (fn [row]
                   (str/join "|" row))
                 rows)))

(defn table [heads rows]
  (str (table-head heads) "\n" (table-rows rows)))

