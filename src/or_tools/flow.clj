(ns or-tools.flow
  "Facade of the network-flow solvers."
  (:require [hive-dsl.result :as r]
            [malli.core :as m]
            [or-tools.flow.model :as model]
            [or-tools.flow.native :as native]
            [or-tools.kernel.schema :as schema]
            [or-tools.ports :as ports]))

(def ^:dynamic *engine*
  "The IFlowEngine read by each solve call."
  native/engine)

(defn max-flow
  "Solve a max-flow `problem`, returning a Result of its solution."
  [problem]
  (r/let-ok [plan (model/promote-max-flow problem)
             raw (ports/solve-max-flow *engine* plan)]
    (r/ok (model/decode plan raw))))

(m/=> max-flow [:=> [:cat :any] (schema/result-of model/MaxFlowSolution)])

(defn min-cost-flow
  "Solve a min-cost-flow `problem`, returning a Result of its solution."
  [problem]
  (r/let-ok [plan (model/promote-min-cost problem)
             raw (ports/solve-min-cost-flow *engine* plan)]
    (r/ok (model/decode plan raw))))

(m/=> min-cost-flow [:=> [:cat :any] (schema/result-of model/MinCostSolution)])
