(ns or-tools.cp-sat.model
  "CP-SAT domain: the problem as data and its promotion to a solver plan."
  (:require [clojure.set :as set]
            [hive-dsl.result :as r]
            [malli.core :as m]
            [or-tools.kernel.expr :as expr]
            [or-tools.kernel.schema :as schema]))

(def Coefficient
  "An integer usable in a CP-SAT expression."
  [:int {:gen/min -1000 :gen/max 1000}])

(def Variable
  "A variable or interval name."
  :keyword)

(def Expr
  "A CP-SAT expression."
  (expr/expr-schema Coefficient Variable))

(def Literal
  "A boolean variable or its negation."
  [:or Variable [:tuple [:= :not] Variable]])

(def Domain
  "A non-empty list of inclusive integer ranges."
  [:vector {:min 1} [:tuple Coefficient Coefficient]])

(def IntForm
  "A linear form with integer coefficients."
  [:map {:closed true}
   [:terms [:map-of :keyword integer?]]
   [:constant integer?]])

(def slot-schemas
  "The caller-facing schema of each constraint slot type."
  {:expr Expr
   :exprs [:vector Expr]
   :lit Literal
   :lits [:vector Literal]
   :vars [:vector Variable]
   :int Coefficient
   :ints [:vector Coefficient]
   :domain Domain
   :tuples [:vector [:vector Coefficient]]
   :intervals [:vector Variable]
   :rects [:vector [:tuple Variable Variable]]
   :demands [:vector [:tuple Variable Expr]]
   :arcs [:vector [:tuple Coefficient Coefficient Literal]]
   :transitions [:vector [:tuple Coefficient Coefficient Coefficient]]
   :events [:vector [:tuple Expr Coefficient]]})

(def constraint-shapes
  "Every constraint kind and the slot types following its keyword."
  {:<= [:expr :expr]
   :>= [:expr :expr]
   :< [:expr :expr]
   :> [:expr :expr]
   := [:expr :expr]
   :!= [:expr :expr]
   :in [:expr :domain]
   :all-different [:exprs]
   :bool-or [:lits]
   :bool-and [:lits]
   :bool-xor [:lits]
   :at-most-one [:lits]
   :at-least-one [:lits]
   :exactly-one [:lits]
   :implies [:lit :lit]
   :element [:expr :exprs :expr]
   :allowed [:exprs :tuples]
   :forbidden [:exprs :tuples]
   :min= [:expr :exprs]
   :max= [:expr :exprs]
   :abs= [:expr :expr]
   :mul= [:expr :expr :expr]
   :div= [:expr :expr :expr]
   :mod= [:expr :expr :expr]
   :no-overlap [:intervals]
   :no-overlap-2d [:rects]
   :cumulative [:expr :demands]
   :circuit [:arcs]
   :multi-circuit [:arcs]
   :inverse [:vars :vars]
   :automaton [:exprs :int :ints :transitions]
   :reservoir [:int :int :events]})

(defn constraint-kind
  "Return the kind keyword of constraint `c`, or nil when `c` is not a vector."
  [c]
  (when (vector? c) (first c)))

(m/=> constraint-kind [:=> [:cat :any] :any])

(def Constraint
  "A constraint: [kind & slots], or [:only-if literals constraint]."
  [:schema
   {:registry
    {::constraint
     (into [:multi {:dispatch constraint-kind}
            [:only-if [:tuple [:= :only-if] [:vector Literal] [:ref ::constraint]]]]
           (map (fn [[kind slots]]
                  [kind (into [:tuple [:= kind]] (map slot-schemas) slots)]))
           constraint-shapes)}}
   [:ref ::constraint]])

(def VariableSpec
  "A variable declaration: :bool, [lo hi], or {:domain ranges}."
  [:or
   [:= :bool]
   [:tuple Coefficient Coefficient]
   [:map {:closed true} [:domain Domain]]])

(def IntervalSpec
  "An interval declaration; :end defaults to :start plus :size."
  [:map {:closed true}
   [:start Expr]
   [:size Expr]
   [:end {:optional true} Expr]
   [:present {:optional true} Literal]])

(def Objective
  "The optimization direction and expression."
  [:tuple [:enum :minimize :maximize] Expr])

(def Problem
  "A CP-SAT problem."
  [:map {:closed true}
   [:variables [:map-of Variable VariableSpec]]
   [:intervals {:optional true} [:map-of Variable IntervalSpec]]
   [:constraints {:optional true} [:vector Constraint]]
   [:objective {:optional true} Objective]
   [:hints {:optional true} [:map-of Variable Coefficient]]])

(def Options
  "Search options."
  [:map {:closed true}
   [:time-limit-s {:optional true} schema/PosReal]
   [:workers {:optional true} schema/PosInt]
   [:seed {:optional true} schema/NonNegInt]
   [:relative-gap {:optional true} [:and schema/Real [:>= 0]]]
   [:log? {:optional true} :boolean]
   [:all-solutions? {:optional true} :boolean]
   [:solution-limit {:optional true} schema/PosInt]])

(def PlanVariable
  "A promoted variable."
  [:or
   [:map {:closed true} [:name Variable] [:kind [:= :bool]]]
   [:map {:closed true} [:name Variable] [:kind [:= :int]] [:domain Domain]]])

(def PlanInterval
  "A promoted interval."
  [:map {:closed true}
   [:name Variable]
   [:start IntForm]
   [:size IntForm]
   [:end IntForm]
   [:present [:maybe Literal]]])

(def PlanConstraint
  "A promoted constraint."
  [:map {:closed true}
   [:kind :keyword]
   [:slots vector?]
   [:only-if [:vector Literal]]])

(def Plan
  "A promoted problem, ready for an engine."
  [:map {:closed true}
   [:variables [:vector PlanVariable]]
   [:intervals [:vector PlanInterval]]
   [:constraints [:vector PlanConstraint]]
   [:objective [:maybe [:map {:closed true}
                        [:sense [:enum :minimize :maximize]]
                        [:form IntForm]]]]
   [:hints [:map-of Variable Coefficient]]])

(def References
  "The variable and interval names a fragment of a problem mentions."
  [:map {:closed true}
   [:variables [:set Variable]]
   [:intervals [:set Variable]]])

(def Solution
  "The outcome of solving a plan."
  [:map
   [:status schema/Status]
   [:objective {:optional true} number?]
   [:best-bound {:optional true} number?]
   [:values {:optional true} [:map-of Variable :int]]
   [:solutions {:optional true} [:vector [:map-of Variable :int]]]
   [:stats [:map
            [:wall-time-s number?]
            [:conflicts :int]
            [:branches :int]]]])

(defn literal-variable
  "Return the variable a literal refers to."
  [literal]
  (if (keyword? literal) literal (second literal)))

(m/=> literal-variable [:=> [:cat Literal] Variable])

(defn- refs-of
  "Build a References value from sequences of variables and intervals."
  [variables intervals]
  {:variables (set variables) :intervals (set intervals)})

(m/=> refs-of [:=> [:cat
                    [:maybe [:or [:set Variable] [:sequential Variable]]]
                    [:maybe [:or [:set Variable] [:sequential Variable]]]]
               References])

(defn slot-references
  "Return the References of `value` occupying a slot of type `slot`."
  [slot value]
  (case slot
    :expr (refs-of (expr/variables value) [])
    :exprs (refs-of (mapcat expr/variables value) [])
    :lit (refs-of [(literal-variable value)] [])
    :lits (refs-of (map literal-variable value) [])
    :vars (refs-of value [])
    :intervals (refs-of [] value)
    :rects (refs-of [] (mapcat identity value))
    :demands (refs-of (mapcat (comp expr/variables second) value) (map first value))
    :arcs (refs-of (map (comp literal-variable #(nth % 2)) value) [])
    :events (refs-of (mapcat (comp expr/variables first) value) [])
    (refs-of [] [])))

(m/=> slot-references [:=> [:cat :keyword :any] References])

(defn merge-references
  "Union a sequence of References."
  [references]
  {:variables (into #{} (mapcat :variables) references)
   :intervals (into #{} (mapcat :intervals) references)})

(m/=> merge-references [:=> [:cat [:sequential References]] References])

(defn constraint-references
  "Return the References of constraint `c`."
  [c]
  (let [[kind & slots] c]
    (if (= :only-if kind)
      (merge-references [(refs-of (map literal-variable (first slots)) [])
                         (constraint-references (second slots))])
      (merge-references (map slot-references (constraint-shapes kind) slots)))))

(m/=> constraint-references [:=> [:cat Constraint] References])

(defn- interval-references
  "Return the References of an interval declaration."
  [{:keys [start size end present]}]
  (refs-of (concat (expr/variables start)
                   (expr/variables size)
                   (when end (expr/variables end))
                   (when present [(literal-variable present)]))
           []))

(m/=> interval-references [:=> [:cat IntervalSpec] References])

(defn problem-references
  "Return the References of the whole `problem`."
  [{:keys [intervals constraints objective hints]}]
  (merge-references
   (concat (map interval-references (vals intervals))
           (map constraint-references constraints)
           (when objective [(refs-of (expr/variables (second objective)) [])])
           [(refs-of (keys hints) [])])))

(m/=> problem-references [:=> [:cat Problem] References])

(defn unknown-references
  "Return the References in `problem` that no declaration defines."
  [problem]
  (let [{:keys [variables intervals]} (problem-references problem)]
    {:variables (set/difference variables (set (keys (:variables problem))))
     :intervals (set/difference intervals (set (keys (:intervals problem))))}))

(m/=> unknown-references [:=> [:cat Problem] References])

(defn- promote-variable
  "Promote one variable declaration."
  [[variable spec]]
  (cond
    (= :bool spec) {:name variable :kind :bool}
    (vector? spec) {:name variable :kind :int :domain [spec]}
    :else {:name variable :kind :int :domain (:domain spec)}))

(m/=> promote-variable [:=> [:cat [:tuple Variable VariableSpec]] PlanVariable])

(defn- promote-interval
  "Promote one interval declaration, deriving a missing :end."
  [[interval {:keys [start size end present]}]]
  {:name interval
   :start (expr/linearize start)
   :size (expr/linearize size)
   :end (expr/linearize (or end [:+ start size]))
   :present present})

(m/=> promote-interval [:=> [:cat [:tuple Variable IntervalSpec]] PlanInterval])

(defn promote-slot
  "Replace every expression inside a slot value with its linear form."
  [slot value]
  (case slot
    :expr (expr/linearize value)
    :exprs (mapv expr/linearize value)
    :demands (mapv (fn [[interval demand]] [interval (expr/linearize demand)]) value)
    :events (mapv (fn [[time delta]] [(expr/linearize time) delta]) value)
    value))

(m/=> promote-slot [:=> [:cat :keyword :any] :any])

(defn promote-constraint
  "Promote constraint `c`, flattening nested :only-if wrappers."
  [c]
  (let [[kind & slots] c]
    (if (= :only-if kind)
      (update (promote-constraint (second slots))
              :only-if
              (fn [literals] (into (vec (first slots)) literals)))
      {:kind kind
       :slots (mapv promote-slot (constraint-shapes kind) slots)
       :only-if []})))

(m/=> promote-constraint [:=> [:cat Constraint] PlanConstraint])

(defn build-plan
  "Build the plan of a schema-valid `problem` whose references all resolve."
  [{:keys [variables intervals constraints objective hints]}]
  {:variables (mapv promote-variable (sort-by key variables))
   :intervals (mapv promote-interval (sort-by key intervals))
   :constraints (mapv promote-constraint constraints)
   :objective (when-let [[sense e] objective]
                {:sense sense :form (expr/linearize e)})
   :hints (or hints {})})

(m/=> build-plan [:=> [:cat Problem] Plan])

(defn- check-references
  "Return ok of `problem` when its references resolve, otherwise an error."
  [problem]
  (let [unknown (unknown-references problem)]
    (if (every? empty? (vals unknown))
      (r/ok problem)
      (r/err :cp-sat/unknown-reference {:unknown unknown}))))

(m/=> check-references [:=> [:cat Problem] (schema/result-of Problem)])

(defn promote
  "Validate `problem` and return a Result of its plan."
  [problem]
  (r/let-ok [valid (schema/conform Problem :cp-sat/invalid-problem problem)
             resolved (check-references valid)]
    (r/ok (build-plan resolved))))

(m/=> promote [:=> [:cat :any] (schema/result-of Plan)])

(defn promote-options
  "Validate `options` and return them as a Result."
  [options]
  (schema/conform Options :cp-sat/invalid-options options))

(m/=> promote-options [:=> [:cat :any] (schema/result-of Options)])

(defn status-keyword
  "Translate a native CpSolverStatus name to a status keyword."
  [status-name]
  (get {"OPTIMAL" :optimal
        "FEASIBLE" :feasible
        "INFEASIBLE" :infeasible
        "MODEL_INVALID" :model-invalid
        "UNKNOWN" :unknown}
       status-name
       :unknown))

(m/=> status-keyword [:=> [:cat :string] schema/Status])

(defn solved?
  "True when `status` means the solver holds a solution."
  [status]
  (contains? #{:optimal :feasible} status))

(m/=> solved? [:=> [:cat schema/Status] :boolean])
