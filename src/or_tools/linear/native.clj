(ns or-tools.linear.native
  "Boundary: the ILinearEngine adapter over MPSolver."
  (:require [hive-dsl.result :as r]
            [malli.core :as m]
            [or-tools.kernel.schema :as schema]
            [or-tools.linear.model :as model]
            [or-tools.native.loader :as loader]
            [or-tools.ports :as ports])
  (:import [com.google.ortools.linearsolver MPConstraint MPObjective MPSolver MPVariable]))

(set! *warn-on-reflection* true)

(def SolverInstance (schema/instance-of MPSolver))
(def VariableInstance (schema/instance-of MPVariable))
(def Variables [:map-of model/Variable VariableInstance])
(def ConstraintInstance (schema/instance-of MPConstraint))

(defn- add-variables!
  "Declare the plan variables, returning a name -> native map."
  [^MPSolver solver plan-variables]
  (into {}
        (map (fn [{variable :name :keys [lb ub integer?]}]
               [variable (.makeVar solver
                                   (double lb)
                                   (double ub)
                                   (boolean integer?)
                                   (subs (str variable) 1))]))
        plan-variables))

(m/=> add-variables! [:=> [:cat SolverInstance [:sequential model/PlanVariable]] Variables])

(defn- add-constraint!
  "Add one range constraint."
  [^MPSolver solver variables {:keys [terms lb ub]}]
  (let [^MPConstraint constraint (.makeConstraint solver (double lb) (double ub))]
    (doseq [[variable coefficient] terms]
      (.setCoefficient constraint
                       ^MPVariable (variables variable)
                       (double coefficient)))
    constraint))

(m/=> add-constraint! [:=> [:cat SolverInstance Variables model/PlanConstraint] ConstraintInstance])

(defn- apply-objective!
  "Set objective coefficients, offset and sense."
  [^MPSolver solver variables {:keys [sense terms offset]}]
  (let [^MPObjective objective (.objective solver)]
    (doseq [[variable coefficient] terms]
      (.setCoefficient objective
                       ^MPVariable (variables variable)
                       (double coefficient)))
    (.setOffset objective (double offset))
    (if (= :maximize sense)
      (.setMaximization objective)
      (.setMinimization objective))
    nil))

(m/=> apply-objective! [:=> [:cat SolverInstance Variables model/PlanObjective] :nil])

(defn- configure!
  "Copy options onto the solver. Throw when the backend rejects :solver-parameters."
  [^MPSolver solver {:keys [time-limit-s threads log? solver-parameters]}]
  (when time-limit-s (.setTimeLimit solver (long (* 1000 time-limit-s))))
  (when threads (.setNumThreads solver (int threads)))
  (when log? (.enableOutput solver))
  (when (and solver-parameters
             (not (.setSolverSpecificParametersAsString solver ^String solver-parameters)))
    (throw (ex-info "The backend rejected :solver-parameters"
                    {:solver-parameters solver-parameters})))
  nil)

(m/=> configure! [:=> [:cat SolverInstance model/Options] :nil])

(defn- solved-fields
  "Read the values of a solved model. Duals and reduced costs exist only for LPs."
  [^MPSolver solver variables constraints]
  (let [^MPObjective objective (.objective solver)
        continuous? (not (.isMip solver))
        read-all (fn [read-variable]
                   (into {}
                         (map (fn [[variable native]] [variable (read-variable native)]))
                         variables))]
    (cond-> {:objective (.value objective)
             :values (read-all (fn [^MPVariable v] (.solutionValue v)))}
      (not continuous?) (assoc :best-bound (.bestBound objective))
      continuous? (assoc :reduced-costs (read-all (fn [^MPVariable v] (.reducedCost v)))
                         :duals (mapv (fn [^MPConstraint c] (.dualValue c)) constraints)))))

(m/=> solved-fields [:=> [:cat SolverInstance Variables [:sequential ConstraintInstance]] map?])

(defn- run-solver
  "Build and solve `plan` on `solver`, returning a Solution."
  [^MPSolver solver plan options]
  (let [variables (add-variables! solver (:variables plan))
        constraints (mapv (partial add-constraint! solver variables) (:constraints plan))]
    (apply-objective! solver variables (:objective plan))
    (configure! solver options)
    (let [status (model/status-keyword (.name (.solve solver)))]
      (cond-> {:status status
               :stats {:wall-time-ms (.wallTime solver)
                       :iterations (.iterations solver)
                       :nodes (if (.isMip solver) (.nodes solver) 0)}}
        (model/solved? status) (merge (solved-fields solver variables constraints))))))

(m/=> run-solver [:=> [:cat SolverInstance model/Plan model/Options] model/Solution])

(defn solve-plan
  "Solve `plan` under `options`, returning a Result of a Solution."
  [plan options]
  (r/let-ok [created (r/try-effect* :linear/native-failure
                       (loader/ensure-loaded!)
                       (MPSolver/createSolver (:solver-id plan)))
             available (if created
                         (r/ok created)
                         (r/err :linear/solver-unavailable {:solver (:solver-id plan)}))]
    (try
      (r/try-effect* :linear/native-failure
        (run-solver available plan options))
      (finally (.delete ^MPSolver available)))))

(m/=> solve-plan [:=> [:cat model/Plan model/Options] (schema/result-of model/Solution)])

(defrecord NativeEngine []
  ports/ILinearEngine
  (solve-linear [_ plan options] (solve-plan plan options)))

(def engine
  "The default engine, backed by the native MPSolver."
  (->NativeEngine))
