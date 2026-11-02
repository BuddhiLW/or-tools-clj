(ns or-tools.assignment.native
  "Boundary: the IAssignmentEngine adapter over LinearSumAssignment."
  (:require [hive-dsl.result :as r]
            [malli.core :as m]
            [or-tools.assignment.model :as model]
            [or-tools.kernel.schema :as schema]
            [or-tools.native.loader :as loader]
            [or-tools.ports :as ports])
  (:import [com.google.ortools.graph LinearSumAssignment]))

(set! *warn-on-reflection* true)

(defn- run-solver
  "Run the native solver on `plan`."
  [{:keys [arcs workers]}]
  (let [solver (LinearSumAssignment.)]
    (doseq [{:keys [left right cost]} arcs]
      (.addArcWithCost solver (int left) (int right) (long cost)))
    (let [status (model/status-keyword (.name (.solve solver)))]
      (cond-> {:status status}
        (= :optimal status)
        (assoc :total-cost (.getOptimalCost solver)
               :mates (mapv (fn [left]
                              [(long (.getRightMate solver (int left)))
                               (.getAssignmentCost solver (int left))])
                            (range (count workers))))))))

(m/=> run-solver [:=> [:cat model/Plan] model/Raw])

(defn solve-plan
  "Solve `plan`, returning a Result of the raw outcome."
  [plan]
  (r/try-effect* :assignment/native-failure
    (loader/ensure-loaded!)
    (run-solver plan)))

(m/=> solve-plan [:=> [:cat model/Plan] (schema/result-of model/Raw)])

(defrecord NativeEngine []
  ports/IAssignmentEngine
  (solve-assignment [_ plan] (solve-plan plan)))

(def engine
  "The default engine, backed by the native solver."
  (->NativeEngine))
