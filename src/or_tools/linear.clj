(ns or-tools.linear
  "Facade of the linear and mixed-integer solvers.

   (solve {:variables {:x {:lb 0} :y {:lb 0}}
           :constraints [[:<= [:+ :x [:* 2 :y]] 14]]
           :objective [:maximize [:+ [:* 3 :x] [:* 4 :y]]]})"
  (:require [hive-dsl.result :as r]
            [malli.core :as m]
            [or-tools.kernel.schema :as schema]
            [or-tools.linear.model :as model]
            [or-tools.linear.native :as native]
            [or-tools.ports :as ports]))

(def ^:dynamic *engine*
  "The ILinearEngine read by each `solve` call."
  native/engine)

(defn solve
  "Solve `problem`, returning a Result of a Solution. :solver defaults to :glop.
   An infeasible problem is an ok Result whose :status is :infeasible."
  ([problem] (solve problem {}))
  ([problem options]
   (r/let-ok [plan (model/promote problem)
              promoted (model/promote-options options)]
     (ports/solve-linear *engine* plan promoted))))

(m/=> solve [:function
             [:=> [:cat :any] (schema/result-of model/Solution)]
             [:=> [:cat :any :any] (schema/result-of model/Solution)]])
