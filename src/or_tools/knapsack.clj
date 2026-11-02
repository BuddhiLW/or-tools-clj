(ns or-tools.knapsack
  "Facade of the knapsack solvers.

   (solve {:items [{:id :a :value 10 :weights [5]}] :capacities [8]})"
  (:require [hive-dsl.result :as r]
            [malli.core :as m]
            [or-tools.kernel.schema :as schema]
            [or-tools.knapsack.model :as model]
            [or-tools.knapsack.native :as native]
            [or-tools.ports :as ports]))

(def ^:dynamic *engine*
  "The IKnapsackEngine read by each `solve` call."
  native/engine)

(defn solve
  "Solve `problem`, returning a Result of its Solution."
  [problem]
  (r/let-ok [plan (model/promote problem)
             raw (ports/solve-knapsack *engine* plan)]
    (r/ok (model/decode plan raw))))

(m/=> solve [:=> [:cat :any] (schema/result-of model/Solution)])
