(ns or-tools.routing
  "Facade of the vehicle-routing library.

   (solve {:matrix [[0 2 9] [2 0 6] [9 6 0]]
           :vehicles 1
           :depot 0
           :search {:first-solution :path-cheapest-arc}})"
  (:require [hive-dsl.result :as r]
            [malli.core :as m]
            [or-tools.kernel.schema :as schema]
            [or-tools.ports :as ports]
            [or-tools.routing.model :as model]
            [or-tools.routing.native :as native]))

(def ^:dynamic *engine*
  "The IRoutingEngine read by each `solve` call."
  native/engine)

(defn solve
  "Solve `problem`, returning a Result of its Solution."
  [problem]
  (r/let-ok [plan (model/promote problem)
             raw (ports/solve-routing *engine* plan)]
    (r/ok (model/decode plan raw))))

(m/=> solve [:=> [:cat :any] (schema/result-of model/Solution)])
