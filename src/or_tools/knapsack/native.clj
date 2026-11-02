(ns or-tools.knapsack.native
  "Boundary: the IKnapsackEngine adapter over KnapsackSolver."
  (:require [hive-dsl.result :as r]
            [malli.core :as m]
            [or-tools.kernel.schema :as schema]
            [or-tools.knapsack.model :as model]
            [or-tools.native.loader :as loader]
            [or-tools.ports :as ports])
  (:import [com.google.ortools.algorithms KnapsackSolver KnapsackSolver$SolverType]))

(set! *warn-on-reflection* true)

(defn- run-solver
  "Run the native solver on `plan`."
  [{:keys [native-solver ids values weights capacities time-limit-s]}]
  (let [solver (KnapsackSolver. (KnapsackSolver$SolverType/valueOf ^String native-solver) "knapsack")]
    (when time-limit-s
      (.setTimeLimit solver (double time-limit-s)))
    (.init solver
           (long-array values)
           ^"[[J" (into-array (Class/forName "[J") (map long-array weights))
           (long-array capacities))
    (let [value (.solve solver)]
      {:value value
       :selected (filterv (fn [position] (.bestSolutionContains solver (int position)))
                          (range (count ids)))
       :optimal? (.isSolutionOptimal solver)})))

(m/=> run-solver [:=> [:cat model/Plan] model/Raw])

(defn solve-plan
  "Solve `plan`, returning a Result of the raw outcome."
  [plan]
  (r/try-effect* :knapsack/native-failure
    (loader/ensure-loaded!)
    (run-solver plan)))

(m/=> solve-plan [:=> [:cat model/Plan] (schema/result-of model/Raw)])

(defrecord NativeEngine []
  ports/IKnapsackEngine
  (solve-knapsack [_ plan] (solve-plan plan)))

(def engine
  "The default engine, backed by the native solver."
  (->NativeEngine))
