(ns or-tools.linear.model
  "Linear and mixed-integer domain: the problem as data and its promotion."
  (:require [clojure.set :as set]
            [hive-dsl.result :as r]
            [malli.core :as m]
            [or-tools.kernel.expr :as expr]
            [or-tools.kernel.schema :as schema]))

(def Variable
  "A variable name."
  :keyword)

(def Expr
  "A linear expression with real coefficients."
  (expr/expr-schema schema/Real Variable))

(def solver-ids
  "Each supported backend and the identifier MPSolver/createSolver expects."
  {:glop "GLOP"
   :pdlp "PDLP"
   :clp "CLP"
   :glpk "GLPK"
   :scip "SCIP"
   :cbc "CBC"
   :sat "SAT"
   :bop "BOP"
   :gurobi "GUROBI"
   :cplex "CPLEX"
   :xpress "XPRESS"})

(def SolverKey
  "A backend name."
  (into [:enum] (keys solver-ids)))

(def VariableSpec
  "A declaration: :bool, or a map whose absent bound means unbounded."
  [:or
   [:= :bool]
   [:map {:closed true}
    [:lb {:optional true} schema/Real]
    [:ub {:optional true} schema/Real]
    [:integer? {:optional true} :boolean]]])

(def Constraint
  "A linear relation."
  [:or
   [:tuple [:enum :<= :>= :=] Expr Expr]
   [:tuple [:= :between] schema/Real Expr schema/Real]])

(def Problem
  "A linear or mixed-integer problem."
  [:map {:closed true}
   [:solver {:optional true} SolverKey]
   [:variables [:map-of Variable VariableSpec]]
   [:constraints {:optional true} [:vector Constraint]]
   [:objective {:optional true} [:tuple [:enum :minimize :maximize] Expr]]])

(def Options
  "Solve options. :solver-parameters holds backend-specific settings as text."
  [:map {:closed true}
   [:time-limit-s {:optional true} schema/PosReal]
   [:threads {:optional true} schema/PosInt]
   [:log? {:optional true} :boolean]
   [:solver-parameters {:optional true} :string]])

(def Terms
  "A variable -> real coefficient map."
  [:map-of Variable number?])

(def PlanVariable
  "A promoted variable with explicit bounds, infinite when unbounded."
  [:map {:closed true}
   [:name Variable]
   [:lb :double]
   [:ub :double]
   [:integer? :boolean]])

(def PlanConstraint
  "A promoted range constraint: lb <= terms <= ub."
  [:map {:closed true}
   [:terms Terms]
   [:lb :double]
   [:ub :double]])

(def PlanObjective
  "A promoted objective."
  [:map {:closed true}
   [:sense [:enum :minimize :maximize]]
   [:terms Terms]
   [:offset :double]])

(def Plan
  "A promoted problem."
  [:map {:closed true}
   [:solver-id :string]
   [:variables [:vector PlanVariable]]
   [:constraints [:vector PlanConstraint]]
   [:objective PlanObjective]])

(def Solution
  "The outcome of solving a plan."
  [:map
   [:status schema/Status]
   [:objective {:optional true} number?]
   [:best-bound {:optional true} number?]
   [:values {:optional true} [:map-of Variable number?]]
   [:reduced-costs {:optional true} [:map-of Variable number?]]
   [:duals {:optional true} [:vector number?]]
   [:stats [:map
            [:wall-time-ms :int]
            [:iterations :int]
            [:nodes :int]]]])

(defn- promote-variable
  "Promote one variable declaration."
  [[variable spec]]
  (if (= :bool spec)
    {:name variable :lb 0.0 :ub 1.0 :integer? true}
    {:name variable
     :lb (double (get spec :lb ##-Inf))
     :ub (double (get spec :ub ##Inf))
     :integer? (boolean (:integer? spec))}))

(m/=> promote-variable [:=> [:cat [:tuple Variable VariableSpec]] PlanVariable])

(defn promote-constraint
  "Promote a relation to a range constraint with every constant on the bounds."
  [c]
  (if (= :between (first c))
    (let [[_ low e high] c
          {:keys [terms constant]} (expr/linearize e)]
      {:terms terms
       :lb (double (- low constant))
       :ub (double (- high constant))})
    (let [[relation left right] c
          {:keys [terms constant]} (expr/subtract (expr/linearize left)
                                                  (expr/linearize right))
          bound (double (- constant))]
      {:terms terms
       :lb (if (= :<= relation) ##-Inf bound)
       :ub (if (= :>= relation) ##Inf bound)})))

(m/=> promote-constraint [:=> [:cat Constraint] PlanConstraint])

(defn- promote-objective
  "Promote the objective, defaulting to minimizing zero."
  [objective]
  (let [[sense e] (or objective [:minimize 0])
        {:keys [terms constant]} (expr/linearize e)]
    {:sense sense :terms terms :offset (double constant)}))

(m/=> promote-objective [:=> [:cat [:maybe [:tuple :keyword Expr]]] PlanObjective])

(defn- constraint-variables
  "Return the variables a constraint names."
  [c]
  (if (= :between (first c))
    (expr/variables (nth c 2))
    (into (expr/variables (nth c 1)) (expr/variables (nth c 2)))))

(m/=> constraint-variables [:=> [:cat Constraint] [:set Variable]])

(defn unknown-variables
  "Return the variables `problem` names without declaring."
  [{:keys [variables constraints objective]}]
  (set/difference
   (into (if objective (expr/variables (second objective)) #{})
         (mapcat constraint-variables)
         constraints)
   (set (keys variables))))

(m/=> unknown-variables [:=> [:cat Problem] [:set Variable]])

(defn build-plan
  "Build the plan of a schema-valid `problem` whose variables all resolve."
  [{:keys [solver variables constraints objective]}]
  {:solver-id (solver-ids (or solver :glop))
   :variables (mapv promote-variable (sort-by key variables))
   :constraints (mapv promote-constraint constraints)
   :objective (promote-objective objective)})

(m/=> build-plan [:=> [:cat Problem] Plan])

(defn- check-variables
  "Return ok of `problem` when its variables resolve, otherwise an error."
  [problem]
  (let [unknown (unknown-variables problem)]
    (if (empty? unknown)
      (r/ok problem)
      (r/err :linear/unknown-variable {:unknown unknown}))))

(m/=> check-variables [:=> [:cat Problem] (schema/result-of Problem)])

(defn promote
  "Validate `problem` and return a Result of its plan."
  [problem]
  (r/let-ok [valid (schema/conform Problem :linear/invalid-problem problem)
             resolved (check-variables valid)]
    (r/ok (build-plan resolved))))

(m/=> promote [:=> [:cat :any] (schema/result-of Plan)])

(defn promote-options
  "Validate `options` and return them as a Result."
  [options]
  (schema/conform Options :linear/invalid-options options))

(m/=> promote-options [:=> [:cat :any] (schema/result-of Options)])

(defn status-keyword
  "Translate a native ResultStatus name to a status keyword."
  [status-name]
  (get {"OPTIMAL" :optimal
        "FEASIBLE" :feasible
        "INFEASIBLE" :infeasible
        "UNBOUNDED" :unbounded
        "ABNORMAL" :abnormal
        "MODEL_INVALID" :model-invalid
        "NOT_SOLVED" :not-solved}
       status-name
       :unknown))

(m/=> status-keyword [:=> [:cat :string] schema/Status])

(defn solved?
  "True when `status` means the solver holds a solution."
  [status]
  (contains? #{:optimal :feasible} status))

(m/=> solved? [:=> [:cat schema/Status] :boolean])
