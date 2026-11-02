(ns or-tools.flow.native
  "Boundary: the IFlowEngine adapter over MaxFlow and MinCostFlow."
  (:require [hive-dsl.result :as r]
            [malli.core :as m]
            [or-tools.flow.model :as model]
            [or-tools.kernel.schema :as schema]
            [or-tools.native.loader :as loader]
            [or-tools.ports :as ports])
  (:import [com.google.ortools.graph MaxFlow MinCostFlow]))

(set! *warn-on-reflection* true)

(defn- run-max-flow
  "Run the native max-flow solver on `plan`."
  [{:keys [arcs source sink]}]
  (let [solver (MaxFlow.)
        ids (mapv (fn [{:keys [from to capacity]}]
                    (.addArcWithCapacity solver (int from) (int to) (long capacity)))
                  arcs)
        status (model/max-flow-status (.name (.solve solver (int source) (int sink))))]
    (cond-> {:status status}
      (= :optimal status) (assoc :value (.getOptimalFlow solver)
                                 :flows (mapv (fn [id] (.getFlow solver (int id))) ids)))))

(m/=> run-max-flow [:=> [:cat model/MaxFlowPlan] model/RawMaxFlow])

(defn- run-min-cost
  "Run the native min-cost-flow solver on `plan`."
  [{:keys [arcs supplies]}]
  (let [solver (MinCostFlow.)
        ids (mapv (fn [{:keys [from to capacity unit-cost]}]
                    (.addArcWithCapacityAndUnitCost solver
                                                    (int from) (int to)
                                                    (long capacity) (long unit-cost)))
                  arcs)]
    (doseq [[node supply] supplies]
      (.setNodeSupply solver (int node) (long supply)))
    (let [status (model/min-cost-status (.name (.solve solver)))]
      (cond-> {:status status}
        (= :optimal status) (assoc :cost (.getOptimalCost solver)
                                   :flows (mapv (fn [id] (.getFlow solver (int id))) ids))))))

(m/=> run-min-cost [:=> [:cat model/MinCostPlan] model/RawMinCost])

(defn solve-max-flow-plan
  "Solve a max-flow plan, returning a Result of the raw outcome."
  [plan]
  (r/try-effect* :flow/native-failure
    (loader/ensure-loaded!)
    (run-max-flow plan)))

(m/=> solve-max-flow-plan [:=> [:cat model/MaxFlowPlan] (schema/result-of model/RawMaxFlow)])

(defn solve-min-cost-plan
  "Solve a min-cost-flow plan, returning a Result of the raw outcome."
  [plan]
  (r/try-effect* :flow/native-failure
    (loader/ensure-loaded!)
    (run-min-cost plan)))

(m/=> solve-min-cost-plan [:=> [:cat model/MinCostPlan] (schema/result-of model/RawMinCost)])

(defrecord NativeEngine []
  ports/IFlowEngine
  (solve-max-flow [_ plan] (solve-max-flow-plan plan))
  (solve-min-cost-flow [_ plan] (solve-min-cost-plan plan)))

(def engine
  "The default engine, backed by the native graph solvers."
  (->NativeEngine))
