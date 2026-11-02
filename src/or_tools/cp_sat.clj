(ns or-tools.cp-sat
  "Facade of the CP-SAT constraint solver.

   (solve {:variables {:x [0 10] :y [0 10]}
           :constraints [[:<= [:+ :x :y] 12]]
           :objective [:maximize [:+ :x [:* 2 :y]]]})"
  (:require [hive-dsl.result :as r]
            [malli.core :as m]
            [or-tools.cp-sat.model :as model]
            [or-tools.cp-sat.native :as native]
            [or-tools.kernel.schema :as schema]
            [or-tools.ports :as ports]))

(def ^:dynamic *engine*
  "The ICpSatEngine read by each `solve` call."
  native/engine)

(defn solve
  "Solve `problem`, returning a Result of a Solution.
   An infeasible problem is an ok Result whose :status is :infeasible."
  ([problem] (solve problem {}))
  ([problem options]
   (r/let-ok [plan (model/promote problem)
              promoted (model/promote-options options)]
     (ports/solve-cp-sat *engine* plan promoted))))

(m/=> solve [:function
             [:=> [:cat :any] (schema/result-of model/Solution)]
             [:=> [:cat :any :any] (schema/result-of model/Solution)]])

(defn solve-all
  "Enumerate every solution of `problem`. The Solution holds them in :solutions."
  ([problem] (solve-all problem {}))
  ([problem options]
   (solve problem (assoc options :all-solutions? true))))

(m/=> solve-all [:function
                 [:=> [:cat :any] (schema/result-of model/Solution)]
                 [:=> [:cat :any map?] (schema/result-of model/Solution)]])
