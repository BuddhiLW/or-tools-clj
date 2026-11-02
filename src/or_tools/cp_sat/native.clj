(ns or-tools.cp-sat.native
  "Boundary: the ICpSatEngine adapter over com.google.ortools.sat."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [malli.core :as m]
            [or-tools.cp-sat.model :as model]
            [or-tools.kernel.schema :as schema]
            [or-tools.native.loader :as loader]
            [or-tools.ports :as ports])
  (:import [com.google.ortools.sat AutomatonConstraint CircuitConstraint Constraint
            CpModel CpSolver CpSolverSolutionCallback CumulativeConstraint IntVar
            IntervalVar LinearArgument LinearExpr LinearExprBuilder Literal
            MultipleCircuitConstraint NoOverlap2dConstraint ReservoirConstraint
            TableConstraint]
           [com.google.ortools.util Domain]))

(set! *warn-on-reflection* true)

(def CpModelInstance (schema/instance-of CpModel))
(def CpSolverInstance (schema/instance-of CpSolver))
(def ConstraintInstance (schema/instance-of Constraint))
(def LinearExprInstance (schema/instance-of LinearExpr))
(def LiteralInstance (schema/instance-of Literal))
(def IntVarInstance (schema/instance-of IntVar))
(def DomainInstance (schema/instance-of Domain))
(def IntervalInstance (schema/instance-of IntervalVar))

(def Context
  "The native objects a plan has built so far."
  [:map
   [:model CpModelInstance]
   [:variables [:map-of :keyword IntVarInstance]]
   [:intervals {:optional true} [:map-of :keyword IntervalInstance]]])

(defn ->domain
  "Build a native Domain from inclusive ranges."
  ^Domain [ranges]
  (Domain/fromIntervals
   (into-array (Class/forName "[J") (map long-array ranges))))

(m/=> ->domain [:=> [:cat model/Domain] DomainInstance])

(defn ->linear
  "Build a native LinearExpr from a linear form."
  ^LinearExpr [context {:keys [terms constant]}]
  (let [^LinearExprBuilder builder (LinearExpr/newBuilder)]
    (doseq [[variable coefficient] terms]
      (.addTerm builder
                ^LinearArgument (get-in context [:variables variable])
                (long coefficient)))
    (.add builder (long constant))
    (.build builder)))

(m/=> ->linear [:=> [:cat Context model/IntForm] LinearExprInstance])

(defn- ->linears
  "Build a LinearArgument array from linear forms."
  ^"[Lcom.google.ortools.sat.LinearArgument;" [context forms]
  (into-array LinearArgument (map (partial ->linear context) forms)))

(m/=> ->linears [:=> [:cat Context [:sequential model/IntForm]] :any])

(defn ->literal
  "Resolve a literal against the context."
  ^Literal [context literal]
  (let [^Literal variable (get-in context [:variables (model/literal-variable literal)])]
    (if (keyword? literal) variable (.not variable))))

(m/=> ->literal [:=> [:cat Context model/Literal] LiteralInstance])

(defn- ->literals
  "Build a Literal array."
  ^"[Lcom.google.ortools.sat.Literal;" [context literals]
  (into-array Literal (map (partial ->literal context) literals)))

(m/=> ->literals [:=> [:cat Context [:sequential model/Literal]] :any])

(defn- ->tuples
  "Build a long[][] from rows of integers."
  ^"[[J" [rows]
  (into-array (Class/forName "[J") (map long-array rows)))

(m/=> ->tuples [:=> [:cat [:sequential [:sequential :int]]] :any])

(defn- interval-of
  "Resolve an interval name."
  ^IntervalVar [context interval]
  (get-in context [:intervals interval]))

(m/=> interval-of [:=> [:cat Context :keyword] IntervalInstance])

(defn- add-arcs!
  "Add [tail head literal] arcs through `add-arc!`, returning `constraint`."
  [context constraint add-arc! arcs]
  (doseq [[tail head literal] arcs]
    (add-arc! (int tail) (int head) (->literal context literal)))
  constraint)

(m/=> add-arcs! [:=> [:cat Context ConstraintInstance fn? [:sequential :any]] ConstraintInstance])

(defn- emit-comparison
  "Add a binary comparison constraint."
  ^Constraint [^CpModel cp-model kind ^LinearArgument a ^LinearArgument b]
  (case kind
    :<= (.addLessOrEqual cp-model a b)
    :>= (.addGreaterOrEqual cp-model a b)
    :< (.addLessThan cp-model a b)
    :> (.addGreaterThan cp-model a b)
    := (.addEquality cp-model a b)
    :!= (.addDifferent cp-model a b)))

(m/=> emit-comparison [:=> [:cat CpModelInstance :keyword :any :any] ConstraintInstance])

(defn- emit-boolean
  "Add a constraint over a literal array."
  ^Constraint [^CpModel cp-model kind ^"[Lcom.google.ortools.sat.Literal;" literals]
  (case kind
    :bool-or (.addBoolOr cp-model literals)
    :bool-and (.addBoolAnd cp-model literals)
    :bool-xor (.addBoolXor cp-model literals)
    :at-most-one (.addAtMostOne cp-model literals)
    :at-least-one (.addAtLeastOne cp-model literals)
    :exactly-one (.addExactlyOne cp-model literals)))

(m/=> emit-boolean [:=> [:cat CpModelInstance :keyword :any] ConstraintInstance])

(defn- emit-arithmetic
  "Add a target = operation(a, b) constraint."
  ^Constraint [^CpModel cp-model kind ^LinearArgument target ^LinearArgument a ^LinearArgument b]
  (case kind
    :mul= (.addMultiplicationEquality cp-model target a b)
    :div= (.addDivisionEquality cp-model target a b)
    :mod= (.addModuloEquality cp-model target a b)))

(m/=> emit-arithmetic [:=> [:cat CpModelInstance :keyword :any :any :any] ConstraintInstance])

(defn- emit-table
  "Add an allowed or forbidden assignment table."
  ^Constraint [^CpModel cp-model kind expressions tuples]
  (let [^"[Lcom.google.ortools.sat.LinearArgument;" arguments expressions
        ^TableConstraint table (if (= :allowed kind)
                                 (.addAllowedAssignments cp-model arguments)
                                 (.addForbiddenAssignments cp-model arguments))]
    (.addTuples table (->tuples tuples))))

(m/=> emit-table [:=> [:cat CpModelInstance :keyword :any [:sequential :any]] ConstraintInstance])

(defn- emit-cumulative
  "Add a cumulative constraint."
  ^Constraint [context capacity demands]
  (let [^CpModel cp-model (:model context)
        ^CumulativeConstraint cumulative
        (.addCumulative cp-model ^LinearArgument (->linear context capacity))]
    (doseq [[interval demand] demands]
      (.addDemand cumulative
                  (interval-of context interval)
                  ^LinearArgument (->linear context demand)))
    cumulative))

(m/=> emit-cumulative [:=> [:cat Context model/IntForm [:sequential :any]] ConstraintInstance])

(defn- emit-automaton
  "Add an automaton constraint."
  ^Constraint [context expressions start finals transitions]
  (let [^CpModel cp-model (:model context)
        ^"[Lcom.google.ortools.sat.LinearArgument;" arguments (->linears context expressions)
        ^AutomatonConstraint automaton
        (.addAutomaton cp-model arguments (long start) (long-array finals))]
    (doseq [[tail head label] transitions]
      (.addTransition automaton (int tail) (int head) (long label)))
    automaton))

(m/=> emit-automaton [:=> [:cat Context [:sequential :any] :int [:sequential :int] [:sequential :any]]
                      ConstraintInstance])

(defn- emit-reservoir
  "Add a reservoir constraint."
  ^Constraint [context low high events]
  (let [^CpModel cp-model (:model context)
        ^ReservoirConstraint reservoir (.addReservoirConstraint cp-model (long low) (long high))]
    (doseq [[time delta] events]
      (.addEvent reservoir ^LinearArgument (->linear context time) (long delta)))
    reservoir))

(m/=> emit-reservoir [:=> [:cat Context :int :int [:sequential :any]] ConstraintInstance])

(defn- emit-no-overlap-2d
  "Add a two-dimensional no-overlap constraint."
  ^Constraint [context rects]
  (let [^CpModel cp-model (:model context)
        ^NoOverlap2dConstraint constraint (.addNoOverlap2D cp-model)]
    (doseq [[x y] rects]
      (.addRectangle constraint (interval-of context x) (interval-of context y)))
    constraint))

(m/=> emit-no-overlap-2d [:=> [:cat Context [:sequential :any]] ConstraintInstance])

(defn- emit-circuit
  "Add a circuit or multiple-circuit constraint."
  ^Constraint [context kind arcs]
  (let [^CpModel cp-model (:model context)]
    (if (= :circuit kind)
      (let [^CircuitConstraint circuit (.addCircuit cp-model)]
        (add-arcs! context circuit
                   (fn [tail head ^Literal literal] (.addArc circuit (int tail) (int head) literal))
                   arcs))
      (let [^MultipleCircuitConstraint circuit (.addMultipleCircuit cp-model)]
        (add-arcs! context circuit
                   (fn [tail head ^Literal literal] (.addArc circuit (int tail) (int head) literal))
                   arcs)))))

(m/=> emit-circuit [:=> [:cat Context :keyword [:sequential :any]] ConstraintInstance])

(defn- int-vars
  "Build an IntVar array from variable names."
  ^"[Lcom.google.ortools.sat.IntVar;" [context names]
  (into-array IntVar (map (:variables context) names)))

(m/=> int-vars [:=> [:cat Context [:sequential :keyword]] :any])

(defn emit
  "Add the constraint of `kind` with promoted `slots`. Return the native constraint."
  ^Constraint [context kind slots]
  (let [^CpModel cp-model (:model context)
        [a b c d] slots
        linear (partial ->linear context)]
    (case kind
      (:<= :>= :< :> := :!=) (emit-comparison cp-model kind (linear a) (linear b))
      :in (.addLinearExpressionInDomain cp-model ^LinearArgument (linear a) (->domain b))
      :all-different (.addAllDifferent cp-model (->linears context a))
      (:bool-or :bool-and :bool-xor :at-most-one :at-least-one :exactly-one)
      (emit-boolean cp-model kind (->literals context a))
      :implies (.addImplication cp-model (->literal context a) (->literal context b))
      :element (.addElement cp-model
                            ^LinearArgument (linear a)
                            (->linears context b)
                            ^LinearArgument (linear c))
      (:allowed :forbidden) (emit-table cp-model kind (->linears context a) b)
      :min= (.addMinEquality cp-model ^LinearArgument (linear a) (->linears context b))
      :max= (.addMaxEquality cp-model ^LinearArgument (linear a) (->linears context b))
      :abs= (.addAbsEquality cp-model ^LinearArgument (linear a) ^LinearArgument (linear b))
      (:mul= :div= :mod=) (emit-arithmetic cp-model kind (linear a) (linear b) (linear c))
      :no-overlap (.addNoOverlap cp-model
                                 ^"[Lcom.google.ortools.sat.IntervalVar;"
                                 (into-array IntervalVar (map (partial interval-of context) a)))
      :no-overlap-2d (emit-no-overlap-2d context a)
      :cumulative (emit-cumulative context a b)
      (:circuit :multi-circuit) (emit-circuit context kind a)
      :inverse (.addInverse cp-model (int-vars context a) (int-vars context b))
      :automaton (emit-automaton context a b c d)
      :reservoir (emit-reservoir context a b c))))

(m/=> emit [:=> [:cat Context :keyword vector?] ConstraintInstance])

(defn- add-variable
  "Declare a plan variable, returning the native variable."
  ^IntVar [^CpModel cp-model {variable :name :keys [kind domain]}]
  (let [label (subs (str variable) 1)]
    (if (= :bool kind)
      (.newBoolVar cp-model label)
      (.newIntVarFromDomain cp-model (->domain domain) label))))

(m/=> add-variable [:=> [:cat CpModelInstance model/PlanVariable] IntVarInstance])

(defn- add-interval
  "Declare a plan interval, returning the native interval."
  ^IntervalVar [context {interval :name :keys [start size end present]}]
  (let [^CpModel cp-model (:model context)
        label (subs (str interval) 1)
        ^LinearArgument start-expr (->linear context start)
        ^LinearArgument size-expr (->linear context size)
        ^LinearArgument end-expr (->linear context end)]
    (if present
      (.newOptionalIntervalVar cp-model start-expr size-expr end-expr
                               (->literal context present) label)
      (.newIntervalVar cp-model start-expr size-expr end-expr label))))

(m/=> add-interval [:=> [:cat Context model/PlanInterval] IntervalInstance])

(defn- add-constraint!
  "Add one plan constraint with its enforcement literals."
  [context {:keys [kind slots only-if]}]
  (let [constraint (emit context kind slots)]
    (when (seq only-if)
      (.onlyEnforceIf constraint (->literals context only-if)))
    nil))

(m/=> add-constraint! [:=> [:cat Context model/PlanConstraint] :nil])

(defn- apply-objective!
  "Set the objective of the model."
  [context {:keys [sense form]}]
  (let [^CpModel cp-model (:model context)
        ^LinearArgument objective (->linear context form)]
    (if (= :minimize sense)
      (.minimize cp-model objective)
      (.maximize cp-model objective))
    nil))

(m/=> apply-objective! [:=> [:cat Context [:map [:sense :keyword] [:form model/IntForm]]] :nil])

(defn build-context
  "Build the native model of `plan`, returning its Context."
  [plan]
  (let [cp-model (CpModel.)
        variables (into {}
                        (map (fn [spec] [(:name spec) (add-variable cp-model spec)]))
                        (:variables plan))
        base {:model cp-model :variables variables}
        intervals (into {}
                        (map (fn [spec] [(:name spec) (add-interval base spec)]))
                        (:intervals plan))
        context (assoc base :intervals intervals)]
    (run! (partial add-constraint! context) (:constraints plan))
    (when-let [objective (:objective plan)]
      (apply-objective! context objective))
    (doseq [[variable value] (:hints plan)]
      (.addHint cp-model ^IntVar (variables variable) (long value)))
    context))

(m/=> build-context [:=> [:cat model/Plan] Context])

(defn- configure!
  "Copy options onto the solver parameters."
  [^CpSolver solver {:keys [time-limit-s workers seed relative-gap log? all-solutions?]}]
  (let [parameters (.getParameters solver)]
    (when time-limit-s (.setMaxTimeInSeconds parameters (double time-limit-s)))
    (when workers (.setNumWorkers parameters (int workers)))
    (when seed (.setRandomSeed parameters (int seed)))
    (when relative-gap (.setRelativeGapLimit parameters (double relative-gap)))
    (when log? (.setLogSearchProgress parameters true))
    (when all-solutions? (.setEnumerateAllSolutions parameters true))
    nil))

(m/=> configure! [:=> [:cat CpSolverInstance model/Options] :nil])

(defn- collector
  "Build a callback storing each solution in the atom `found`, stopping at `limit`."
  ^CpSolverSolutionCallback [variables found limit]
  (proxy [CpSolverSolutionCallback] []
    (onSolutionCallback []
      (let [^CpSolverSolutionCallback this this
            solution (into {}
                           (map (fn [[variable ^LinearArgument native]]
                                  [variable (.value this native)]))
                           variables)
            solutions (swap! found conj solution)]
        (when (and limit (>= (count solutions) limit))
          (.stopSearch this))))))

(m/=> collector [:=> [:cat [:map-of :keyword IntVarInstance] :any [:maybe :int]]
                 (schema/instance-of CpSolverSolutionCallback)])

(defn- current-values
  "Read the value of every variable from the solver."
  [^CpSolver solver variables]
  (into {}
        (map (fn [[variable ^LinearArgument native]]
               [variable (.value solver native)]))
        variables))

(m/=> current-values [:=> [:cat CpSolverInstance [:map-of :keyword IntVarInstance]]
                      [:map-of :keyword :int]])

(defn- solution-of
  "Assemble the Solution from a finished solver."
  [^CpSolver solver plan variables status found]
  (let [solved (model/solved? status)]
    (cond-> {:status status
             :stats {:wall-time-s (.wallTime solver)
                     :conflicts (.numConflicts solver)
                     :branches (.numBranches solver)}}
      solved (assoc :values (current-values solver variables))
      (and solved (:objective plan)) (assoc :objective (.objectiveValue solver)
                                            :best-bound (.bestObjectiveBound solver))
      found (assoc :solutions (vec found)))))

(m/=> solution-of [:=> [:cat CpSolverInstance model/Plan [:map-of :keyword IntVarInstance]
                        schema/Status [:maybe [:sequential :any]]]
                   model/Solution])

(defn- run-solver
  "Solve the built `context` under `options`, returning a Solution."
  [context plan {:keys [all-solutions? solution-limit] :as options}]
  (let [solver (CpSolver.)
        ^CpModel cp-model (:model context)
        variables (:variables context)
        found (when (or all-solutions? solution-limit) (atom []))]
    (configure! solver options)
    (let [status (if found
                   (.solve solver cp-model (collector variables found solution-limit))
                   (.solve solver cp-model))
          reported (model/status-keyword (.name status))]
      (solution-of solver plan variables reported (some-> found deref)))))

(m/=> run-solver [:=> [:cat Context model/Plan model/Options] model/Solution])

(defn solve-plan
  "Solve `plan` under `options`, returning a Result of a Solution."
  [plan options]
  (r/let-ok [context (r/try-effect* :cp-sat/native-failure
                       (loader/ensure-loaded!)
                       (build-context plan))
             _ (let [problems (.validate ^CpModel (:model context))]
                 (if (str/blank? problems)
                   (r/ok true)
                   (r/err :cp-sat/model-rejected {:message problems})))]
    (r/try-effect* :cp-sat/native-failure
      (run-solver context plan options))))

(m/=> solve-plan [:=> [:cat model/Plan model/Options] (schema/result-of model/Solution)])

(defrecord NativeEngine []
  ports/ICpSatEngine
  (solve-cp-sat [_ plan options] (solve-plan plan options)))

(def engine
  "The default engine, backed by the native CP-SAT solver."
  (->NativeEngine))
