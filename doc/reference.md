# Reference

Every option name below is a key in a malli schema under `src/or_tools/`. Problem and option
maps are closed: an unknown key is an `:error`, not ignored. Every code block in this file runs
in the release check.

- [Results](#results)
- [CP-SAT](#cp-sat)
- [Linear and mixed-integer](#linear-and-mixed-integer)
- [Network flows](#network-flows)
- [Assignment](#assignment)
- [Knapsack](#knapsack)
- [Vehicle routing](#vehicle-routing)
- [Bin packing](#bin-packing)
- [Job-shop scheduling](#job-shop-scheduling)

## Results

Every entry point returns a [hive-dsl](https://clojars.org/io.github.hive-agi/hive-dsl) Result:
`{:ok solution}` or `{:error category & details}`.

| Error category | Meaning |
|---|---|
| `:<family>/invalid-problem`, `:cp-sat/invalid-options`, `:linear/invalid-options` | The map does not match its schema. `:explain` holds the humanized malli explanation. |
| `:cp-sat/unknown-reference`, `:linear/unknown-variable` | A constraint or objective names a variable or interval that is not declared. |
| `:cp-sat/model-rejected` | The native model validator refused the model. `:message` holds its text. |
| `:linear/solver-unavailable` | The chosen backend is not in this OR-Tools build. |
| `:flow/invalid-terminals`, `:flow/unknown-terminal` | Source equals sink, or a terminal is not on any arc. |
| `:assignment/unbalanced` | Workers and tasks differ in number. |
| `:knapsack/solver-limit`, `:knapsack/weight-mismatch` | The problem exceeds the chosen backend's limits, or an item has the wrong number of weights. |
| `:routing/invalid-structure` | A structural defect, listed under `:defects`. |
| `:<family>/native-failure` | The native call threw. |

A model without a solution is `{:ok {:status :infeasible ...}}`, not an error. Fields such as
`:values` and `:objective` appear only when the status means a solution exists.

## CP-SAT

`or-tools.cp-sat/solve` takes a problem and an optional options map. `solve-all` is `solve`
with `:all-solutions? true`.

### Problem

| Key | Value |
|---|---|
| `:variables` | Required. A map from keyword to a variable form. |
| `:intervals` | A map from keyword to an interval form. |
| `:constraints` | A vector of constraints. |
| `:objective` | `[:minimize expr]` or `[:maximize expr]`. |
| `:hints` | A map from variable to a suggested integer value. |

### Variables

| Form | Meaning |
|---|---|
| `:bool` | 0 or 1. |
| `[lo hi]` | An integer from `lo` to `hi`, inclusive. |
| `{:domain [[lo hi] ...]}` | An integer in any of the inclusive ranges. Use `[v v]` for a single value. |

```clojure
(require '[or-tools.cp-sat :as cp])

(-> (cp/solve {:variables {:x {:domain [[0 2] [5 7]]}}
               :constraints [[:>= :x 3]]
               :objective [:minimize :x]})
    (get-in [:ok :values]))
;; => {:x 5}
```

### Expressions

An expression is an integer, a variable keyword, `[:+ e ...]`, `[:- e ...]` (one operand
negates it) or `[:* integer e]`. Coefficients are integers. Expressions are linear: to
multiply two variables use the `:mul=` constraint.

### Literals

A literal is a variable keyword, or `[:not variable]` for its negation. Literals appear in the
boolean constraints, in `:only-if` and in an interval's `:present`.

### Constraints

Each constraint is a vector `[kind & slots]`.

| Kind | Slots | Meaning |
|---|---|---|
| `:<=` `:>=` `:<` `:>` `:=` `:!=` | `a b` | Compare two expressions. |
| `:in` | `e [[lo hi] ...]` | `e` lies in one of the ranges. |
| `:all-different` | `[e ...]` | All expressions take different values. |
| `:bool-or` | `[lit ...]` | At least one literal is true. |
| `:bool-and` | `[lit ...]` | Every literal is true. |
| `:bool-xor` | `[lit ...]` | An odd number of literals are true. |
| `:at-most-one` `:at-least-one` `:exactly-one` | `[lit ...]` | Count the true literals. |
| `:implies` | `a b` | Literal `a` implies literal `b`. |
| `:element` | `index [e ...] target` | `target` equals the expression at position `index`. |
| `:allowed` | `[e ...] [[v ...] ...]` | The expressions take one of the listed tuples. |
| `:forbidden` | `[e ...] [[v ...] ...]` | The expressions take none of the listed tuples. |
| `:min=` `:max=` | `target [e ...]` | `target` is the least or greatest expression. |
| `:abs=` | `target e` | `target` is the absolute value of `e`. |
| `:mul=` | `target a b` | `target = a * b`. |
| `:div=` | `target a b` | `target = a / b`, rounded toward zero. The domain of `b` must exclude 0. |
| `:mod=` | `target a b` | `target = a mod b`. |
| `:no-overlap` | `[interval ...]` | The present intervals do not overlap in time. |
| `:no-overlap-2d` | `[[x-interval y-interval] ...]` | The rectangles do not overlap. |
| `:cumulative` | `capacity [[interval demand] ...]` | At every time, the demands of running intervals sum to at most `capacity`. |
| `:circuit` | `[[from to lit] ...]` | The true arcs form one circuit over the nodes. An arc `[i i lit]` lets node `i` be skipped. |
| `:multi-circuit` | `[[from to lit] ...]` | Several circuits, each through node 0. |
| `:inverse` | `[var ...] [var ...]` | The two variable vectors are inverse permutations. |
| `:automaton` | `[e ...] start [final ...] [[from label to] ...]` | The expression sequence is accepted by the automaton. Each transition is `[from-state label to-state]`. |
| `:reservoir` | `min max [[time delta] ...]` | Applying each `delta` at its `time`, the level stays between `min` and `max`. |
| `:only-if` | `[lit ...] constraint` | Enforce `constraint` only when every literal is true. |

`:only-if` accepts a linear, boolean or other constraint inside it. Nested `:only-if` wrappers
merge their literals. Which inner kinds the native solver accepts under enforcement is decided
by OR-Tools.

```clojure
(require '[or-tools.cp-sat :as cp])

(-> (cp/solve {:variables {:a [0 10] :b [1 10] :big [0 10] :product [0 100]
                           :quotient [0 10] :remainder [0 10] :gap [0 10]}
               :constraints [[:= :a 7] [:= :b 3]
                             [:max= :big [:a :b]]
                             [:mul= :product :a :b]
                             [:div= :quotient :a :b]
                             [:mod= :remainder :a :b]
                             [:abs= :gap [:- :b :a]]]})
    (get-in [:ok :values])
    (select-keys [:big :product :quotient :remainder :gap]))
;; => {:big 7 :product 21 :quotient 2 :remainder 1 :gap 4}

(-> (cp/solve {:variables {:open :bool :staff [0 5]}
               :constraints [[:= :open 1]
                             [:only-if [:open] [:>= :staff 2]]
                             [:only-if [[:not :open]] [:= :staff 0]]]
               :objective [:minimize :staff]})
    (get-in [:ok :values]))
;; => {:open 1 :staff 2}

(-> (cp/solve {:variables {:i [0 2] :price [0 100]}
               :constraints [[:element :i [30 10 20] :price]]
               :objective [:minimize :price]})
    (get-in [:ok :values]))
;; => {:i 1 :price 10}
```

### Intervals

An interval is a task in time, declared under `:intervals`.

| Key | Value |
|---|---|
| `:start` | Required. An expression. |
| `:size` | Required. An expression. |
| `:end` | An expression. Defaults to `[:+ start size]`. |
| `:present` | A literal. With it the interval is optional: when the literal is false, the interval is absent and the constraints over it ignore it. |

Intervals are referenced by name in `:no-overlap`, `:no-overlap-2d` and `:cumulative`.

```clojure
(require '[or-tools.cp-sat :as cp])

(-> (cp/solve {:variables {:s1 [0 10] :s2 [0 10] :end [0 20]}
               :intervals {:t1 {:start :s1 :size 3}
                           :t2 {:start :s2 :size 2}}
               :constraints [[:cumulative 2 [[:t1 2] [:t2 1]]]
                             [:>= :end [:+ :s1 3]]
                             [:>= :end [:+ :s2 2]]]
               :objective [:minimize :end]})
    (get-in [:ok :objective]))
;; => 5.0
```

### Options

| Key | Value |
|---|---|
| `:time-limit-s` | A positive number of seconds. |
| `:workers` | A positive integer: parallel search workers. |
| `:seed` | A non-negative integer random seed. |
| `:relative-gap` | A number at or above 0. Stop when the gap between objective and bound is within it. |
| `:log?` | Print the search log. |
| `:all-solutions?` | Enumerate every solution into `:solutions`. |
| `:solution-limit` | Collect solutions into `:solutions` and stop after this many. With `solve-all` it caps the enumeration. With plain `solve` only the improving solutions the search meets are collected, so there may be fewer. |

The examples enumerate with `:workers 1`. `:solutions` arrives in solver order, so compare it as
a set.

```clojure
(require '[or-tools.cp-sat :as cp])

(-> (cp/solve-all {:variables {:a [0 1] :b [0 1]}
                   :constraints [[:!= :a :b]]}
                  {:workers 1})
    (get-in [:ok :solutions])
    set)
;; => #{{:a 0 :b 1} {:a 1 :b 0}}

(-> (cp/solve-all {:variables {:x [0 9]}} {:solution-limit 3 :workers 1})
    (get-in [:ok :solutions])
    count)
;; => 3
```

### Solution

| Key | Value |
|---|---|
| `:status` | `:optimal`, `:feasible`, `:infeasible`, `:model-invalid` or `:unknown`. |
| `:values` | A map from variable to integer, when a solution exists. |
| `:objective`, `:best-bound` | Numbers, when there is an objective and a solution. |
| `:solutions` | A vector of value maps, when enumerating or limited. |
| `:stats` | `{:wall-time-s :conflicts :branches}`. |

## Linear and mixed-integer

`or-tools.linear/solve` takes a problem and an optional options map.

### Problem

| Key | Value |
|---|---|
| `:solver` | A backend keyword. Defaults to `:glop`. |
| `:variables` | Required. A map from keyword to `:bool` or `{:lb :ub :integer?}`. A missing bound is unbounded. |
| `:constraints` | A vector of `[:<= a b]`, `[:>= a b]`, `[:= a b]` or `[:between low e high]`. |
| `:objective` | `[:minimize expr]` or `[:maximize expr]`. Defaults to minimizing 0. |

Expressions have the CP-SAT shape with real coefficients: `[:* 2.5 :x]` is allowed.

### Backends

`:glop` `:pdlp` `:clp` `:glpk` `:scip` `:cbc` `:sat` `:bop` `:gurobi` `:cplex` `:xpress`.

Which backends exist depends on the OR-Tools build. With the `ortools-java` 9.15 jar on Linux
x86-64, `:glpk`, `:gurobi`, `:cplex` and `:xpress` return `{:error :linear/solver-unavailable}`.
The commercial backends are untested.

GLOP solves continuous problems and ignores `:integer?`. Use `:scip`, `:cbc` or `:sat` for whole
amounts.

```clojure
(require '[or-tools.linear :as lp])

(-> (lp/solve {:solver :scip
               :variables {:pick :bool :x {:lb 0 :ub 10 :integer? true}}
               :constraints [[:between 2 [:+ :x [:* 3 :pick]] 6]]
               :objective [:maximize [:+ :x :pick]]})
    (get-in [:ok :objective])
    Math/round)
;; => 6
```

### Options

| Key | Value |
|---|---|
| `:time-limit-s` | A positive number of seconds. |
| `:threads` | A positive integer. |
| `:log?` | Print the backend's output. |
| `:solver-parameters` | Backend-specific settings as text. A backend that rejects it returns `:linear/native-failure`. |

### Solution

| Key | Value |
|---|---|
| `:status` | `:optimal`, `:feasible`, `:infeasible`, `:unbounded`, `:abnormal`, `:model-invalid`, `:not-solved` or `:unknown`. |
| `:objective`, `:values` | When solved. Floating point. |
| `:duals` | Continuous problems: one dual price per constraint, in `:constraints` order. |
| `:reduced-costs` | Continuous problems: a map from variable to reduced cost. |
| `:best-bound` | Integer problems. |
| `:stats` | `{:wall-time-ms :iterations :nodes}`. |

```clojure
(require '[or-tools.linear :as lp])

(-> (lp/solve {:variables {:x {:lb 0 :ub 4} :y {:lb 0}}
               :constraints [[:<= [:+ :x :y] 5]]
               :objective [:maximize [:+ [:* 3 :x] [:* 2 :y]]]})
    :ok
    (select-keys [:objective :duals :reduced-costs]))
;; => {:objective 14.0 :duals [2.0] :reduced-costs {:x 1.0 :y 0.0}}
```

### GLOP and unbounded models

GLOP with presolve reports an unbounded model as `:infeasible`. This is native behaviour. To
tell the two apart, turn presolve off or use another backend.

```clojure
(require '[or-tools.linear :as lp])

(def unbounded {:variables {:x {:lb 0}} :objective [:maximize :x]})

(get-in (lp/solve unbounded) [:ok :status])
;; => :infeasible

(get-in (lp/solve unbounded {:solver-parameters "use_preprocessing: false"}) [:ok :status])
;; => :unbounded

(get-in (lp/solve (assoc unbounded :solver :scip)) [:ok :status])
;; => :unbounded
```

## Network flows

Node labels may be keywords, strings or integers. Capacities are non-negative integers.

`or-tools.flow/max-flow` takes `{:arcs [{:from :to :capacity} ...] :source :sink}` and returns
`:status`, `:value` and `:flows`, each arc annotated with `:flow`.

```clojure
(require '[or-tools.flow :as flow])

(-> (flow/max-flow {:source :s :sink :t
                    :arcs [{:from :s :to :a :capacity 3}
                           {:from :a :to :t :capacity 2}]})
    :ok)
;; => {:status :optimal :value 2 :flows [{:from :s :to :a :capacity 3 :flow 2} {:from :a :to :t :capacity 2 :flow 2}]}
```

`or-tools.flow/min-cost-flow` takes `{:arcs [{:from :to :capacity :unit-cost} ...] :supplies
{node amount}}`. Supplies are positive at sources and negative at sinks, and must sum to zero.
It returns `:status`, `:cost` and `:flows`.

```clojure
(require '[or-tools.flow :as flow])

(-> (flow/min-cost-flow {:supplies {:s 2 :t -2}
                         :arcs [{:from :s :to :t :capacity 5 :unit-cost 3}]})
    (get-in [:ok :cost]))
;; => 6

(flow/min-cost-flow {:supplies {:s 2 :t -1}
                     :arcs [{:from :s :to :t :capacity 5 :unit-cost 3}]})
;; => {:ok {:status :unbalanced}}
```

A shortest path is a min-cost flow of one unit with capacity 1 on every arc.

## Assignment

`or-tools.assignment/solve` takes `{:costs {worker {task cost}}}` and assigns each worker one
task at least total cost. An absent pair is forbidden. Workers and tasks must be equal in
number, otherwise the result is `:assignment/unbalanced`. For extra rules (teams, workload
limits, more workers than tasks) build a CP-SAT model instead; see the assignment examples.

```clojure
(require '[or-tools.assignment :as assignment])

(-> (assignment/solve {:costs {:ana {:lathe 90 :press 76}
                               :bo {:lathe 35 :press 85}}})
    :ok)
;; => {:status :optimal :total-cost 111 :assignments [{:worker :ana :task :press :cost 76} {:worker :bo :task :lathe :cost 35}]}
```

Statuses are `:optimal`, `:infeasible`, `:possible-overflow` and `:unknown`.

## Knapsack

`or-tools.knapsack/solve` takes:

| Key | Value |
|---|---|
| `:items` | Required. `[{:id :value :weights [w ...]} ...]`, one weight per capacity. |
| `:capacities` | Required. One capacity per dimension, for example weight and volume. |
| `:solver` | Defaults to `:branch-and-bound`. |
| `:time-limit-s` | A positive number of seconds. |

| Solver | Limits |
|---|---|
| `:branch-and-bound` `:cp-sat` `:scip` `:cbc` | None. |
| `:dynamic-programming` `:divide-and-conquer` | One dimension. |
| `:64-items` | One dimension, 64 items. |
| `:brute-force` | One dimension, 30 items. |

The result is `{:status :value :items}`, where `:items` lists the chosen ids and `:status` is
`:optimal` or `:feasible`.

```clojure
(require '[or-tools.knapsack :as knapsack])

(-> (knapsack/solve {:capacities [10]
                     :items [{:id :a :value 60 :weights [5]}
                             {:id :b :value 50 :weights [4]}
                             {:id :c :value 40 :weights [6]}]})
    :ok)
;; => {:status :optimal :value 110 :items [:a :b]}
```

## Vehicle routing

`or-tools.routing/solve` takes a problem. Nodes are positions in `:matrix`.

### Problem

| Key | Value |
|---|---|
| `:matrix` | Required. A square matrix of integer arc costs. |
| `:vehicles` | Fleet size. Defaults to the length of `:starts` or `:ends`, else 1. |
| `:depot` | The shared start and end node. Defaults to 0. |
| `:starts`, `:ends` | One node per vehicle, instead of `:depot`. |
| `:dimensions` | A vector of dimensions, below. |
| `:pickups-deliveries` | `{:pairs [[pickup delivery] ...] :dimension name}`. Each pair rides one vehicle, pickup first by the named dimension. |
| `:droppable` | A map from node to penalty. The search may skip the node and pay the penalty. |
| `:fixed-vehicle-cost` | A cost every used vehicle pays. |
| `:search` | Search parameters, below. |

### Dimensions

A dimension is a quantity accumulated along each route, such as load, time or distance.

| Key | Value |
|---|---|
| `:name` | Required. A keyword, unique among dimensions. |
| `:transit` | Required. `{:matrix m}` grows by `m[i][j]` on arc i to j. `{:demands [d ...]}` grows by `d[i]` on leaving node i. |
| `:capacity` | Required. The most the quantity may reach: one integer, or one per vehicle. |
| `:slack` | How much the quantity may grow freely at a node, such as waiting time. Defaults to 0. |
| `:start-at-zero?` | Defaults to true. Set false for a time dimension whose start is free. |
| `:windows` | A map from node to `[earliest latest]`, inclusive. |
| `:global-span-coefficient` | Cost per unit of the longest route's span. Use it to balance routes. |
| `:span-coefficient` | Cost per unit of every route's span. |
| `:finalize-cumuls?` | Ask the search to minimize each route's start and end values. |

### Window semantics

A window on a node that is a vehicle's start limits that vehicle's departure only. With a shared
depot every route starts and ends at the same node, so a round trip may return after the depot
window closes. A window on a separate end node limits arrival.

```clojure
(require '[or-tools.routing :as routing])

(-> (routing/solve {:matrix [[0 4] [4 0]]
                    :dimensions [{:name :time
                                  :transit {:matrix [[0 4] [4 0]]}
                                  :capacity 60 :slack 30 :start-at-zero? false
                                  :windows {0 [0 5] 1 [10 20]}}]})
    (get-in [:ok :routes 0 :stops])
    (->> (mapv (juxt :node (comp :time :cumuls)))))
;; => [[0 0] [1 10] [0 14]]
```

The depot window `[0 5]` held the departure at 0, and the vehicle returned at 14.

### Search

| Key | Value |
|---|---|
| `:first-solution` | `:automatic` `:path-cheapest-arc` `:path-most-constrained-arc` `:savings` `:parallel-savings` `:sweep` `:christofides` `:all-unperformed` `:best-insertion` `:parallel-cheapest-insertion` `:sequential-cheapest-insertion` `:local-cheapest-insertion` `:local-cheapest-cost-insertion` `:global-cheapest-arc` `:local-cheapest-arc` |
| `:metaheuristic` | `:automatic` `:greedy-descent` `:guided-local-search` `:simulated-annealing` `:tabu-search` `:generic-tabu-search` |
| `:time-limit-s` | A positive whole number of seconds. |
| `:solution-limit` | A positive integer. |
| `:log?` | Print the search log. |

`:guided-local-search`, `:simulated-annealing`, `:tabu-search` and `:generic-tabu-search` never
stop on their own, so they are refused unless `:time-limit-s` or `:solution-limit` is set.

```clojure
(require '[or-tools.routing :as routing])

(routing/solve {:matrix [[0 1] [1 0]]
                :search {:metaheuristic :guided-local-search}})
;; => {:error :routing/invalid-structure :defects [{:defect :unbounded-metaheuristic :metaheuristic :guided-local-search}]}
```

### Solution

| Key | Value |
|---|---|
| `:status` | `:optimal`, `:feasible`, `:infeasible`, `:no-solution`, `:timeout`, `:invalid`, `:not-solved` or `:unknown`. |
| `:objective` | Arc costs plus fixed vehicle costs, span costs and drop penalties. |
| `:routes` | One per vehicle: `{:vehicle :cost :stops [{:node :cumuls {dimension value}} ...]}`. `:cost` sums the route's arcs. `:cumuls` holds each dimension's earliest value at the stop. |
| `:dropped` | The droppable nodes no route visits. |

A vehicle that is not used has a route of its start and end only.

```clojure
(require '[or-tools.routing :as routing])

(-> (routing/solve {:matrix [[0 2 9] [2 0 6] [9 6 0]]
                    :dimensions [{:name :load :transit {:demands [0 3 3]} :capacity 4}]
                    :droppable {1 100 2 1}})
    :ok
    (select-keys [:objective :dropped]))
;; => {:objective 5 :dropped [2]}
```

## Bin packing

`or-tools.bin-packing/solve` takes `{:items {id weight} :capacity c}` and an optional CP-SAT
options map. Item ids are keywords or strings. `:max-bins` caps the bins and defaults to one per
item. It builds a CP-SAT model that minimizes the bins used.

```clojure
(require '[or-tools.bin-packing :as bins])

(-> (bins/solve {:capacity 10 :items {:a 6 :b 5 :c 4}})
    (get-in [:ok :bins-used]))
;; => 2
```

`:bins` lists each used bin as `{:bin :items :load}`. Which items share a bin may differ between
equally good packings.

## Job-shop scheduling

`or-tools.scheduling/solve` takes `{:jobs {job [{:machine m :duration d} ...]}}` and an optional
CP-SAT options map. Each job's operations run in order, each machine runs one operation at a
time, and the makespan is minimized. `:horizon` bounds the end times and defaults to the sum of
all durations.

The result holds `:makespan` and `:machines`, a map from machine to its timetable sorted by start.

```clojure
(require '[or-tools.scheduling :as scheduling])

(-> (scheduling/solve {:jobs {:j0 [{:machine :m0 :duration 3} {:machine :m1 :duration 2}]
                              :j1 [{:machine :m1 :duration 4}]}})
    (get-in [:ok :makespan]))
;; => 6
```

Each timetable entry is `{:job :position :start :end}`. A problem can have several optimal
timetables, so a test should check the makespan and the rules rather than exact start times.
