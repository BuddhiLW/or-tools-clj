(ns or-tools.support
  "Shared test helpers: contract instrumentation, generators and predicates."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.generator :as mg]
            [malli.instrument :as mi]))

(defn instrument!
  "Enforce every or-tools function contract in this JVM."
  []
  (mi/instrument! {:filters [(fn [n _ _] (str/starts-with? (str n) "or-tools."))]}))

(defn gen-of
  "Return a test.check generator of `schema`."
  [schema]
  (mg/generator schema))

(defn valid
  "Return a predicate testing values against `schema`."
  [schema]
  (fn [value] (m/validate schema value)))

(defn without-stats
  "Remove timing data from a Result so that it can be snapshotted."
  [result]
  (if (:ok result)
    (update result :ok dissoc :stats)
    result))
