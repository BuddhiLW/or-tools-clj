(ns or-tools.assignment
  "Facade of the linear-sum assignment solver.

   (solve {:costs {:ana {:lathe 90 :press 76} :bo {:lathe 35 :press 85}}})"
  (:require [hive-dsl.result :as r]
            [malli.core :as m]
            [or-tools.assignment.model :as model]
            [or-tools.assignment.native :as native]
            [or-tools.kernel.schema :as schema]
            [or-tools.ports :as ports]))

(def ^:dynamic *engine*
  "The IAssignmentEngine read by each `solve` call."
  native/engine)

(defn solve
  "Solve `problem`, returning a Result of its Solution."
  [problem]
  (r/let-ok [plan (model/promote problem)
             raw (ports/solve-assignment *engine* plan)]
    (r/ok (model/decode plan raw))))

(m/=> solve [:=> [:cat :any] (schema/result-of model/Solution)])
