# or-tools-clj

A data-first Clojure wrapper around [Google OR-Tools](https://developers.google.com/optimization)
9.15. Problems and solutions are plain Clojure data, and every call returns a
[hive-dsl](https://clojars.org/io.github.hive-agi/hive-dsl) Result: `{:ok solution}` or
`{:error category ...}`.

An infeasible model is not an error. It is `{:ok {:status :infeasible}}`. Errors mean the
input was invalid or the native call failed.

## Coverage of the OR-Tools guides

| Guide | Namespace | Entry points |
|---|---|---|
| Constraint optimization (CP-SAT) | `or-tools.cp-sat` | `solve`, `solve-all` |
| Linear and mixed-integer optimization | `or-tools.linear` | `solve` |
| Network flows | `or-tools.flow` | `max-flow`, `min-cost-flow` |
| Assignment | `or-tools.assignment` | `solve` |
| Knapsack | `or-tools.knapsack` | `solve` |
| Vehicle routing | `or-tools.routing` | `solve` |
| Bin packing | `or-tools.bin-packing` | `solve` |
| Job-shop scheduling | `or-tools.scheduling` | `solve` |

## Setup

[![Clojars Project](https://img.shields.io/clojars/v/io.github.buddhilw/or-tools-clj.svg)](https://clojars.org/io.github.buddhilw/or-tools-clj)

Add `io.github.buddhilw/or-tools-clj` to `deps.edn`. The badge shows the newest version. The JVM
flag silences the native-access warning on recent JDKs.

```edn
{:deps {io.github.buddhilw/or-tools-clj {:mvn/version "0.1.1"}}
 :aliases {:dev {:jvm-opts ["--enable-native-access=ALL-UNNAMED"]}}}
```

The native library loads on the first solve.

## Examples

### CP-SAT

```clojure
(require '[or-tools.cp-sat :as cp])

(-> (cp/solve {:variables {:x [0 10] :y [0 10]}
               :constraints [[:<= [:+ :x :y] 12]]
               :objective [:maximize [:+ :x [:* 2 :y]]]})
    :ok
    (select-keys [:status :objective :values]))
;; => {:status :optimal :objective 22.0 :values {:x 2 :y 10}}

;; :solutions arrives in solver order, so compare it as a set.
(-> (cp/solve-all {:variables {:a [0 1] :b [0 1]}
                   :constraints [[:!= :a :b]]})
    (get-in [:ok :solutions])
    set)
;; => #{{:a 0 :b 1} {:a 1 :b 0}}
```

Variables are `:bool`, `[lo hi]` or `{:domain [[0 2] [5 7]]}`. An expression is a number, a
variable, `[:+ e*]`, `[:- e+]` or `[:* coefficient e]`. Every constraint is `[kind & slots]`:

| Family | Kinds |
|---|---|
| Linear | `:<=` `:>=` `:<` `:>` `:=` `:!=` `[:in e ranges]` |
| Boolean | `:bool-or` `:bool-and` `:bool-xor` `:at-most-one` `:at-least-one` `:exactly-one` `[:implies a b]` |
| Global | `:all-different` `:element` `:allowed` `:forbidden` `:inverse` `:automaton` `:circuit` `:multi-circuit` `:reservoir` |
| Arithmetic | `:min=` `:max=` `:abs=` `:mul=` `:div=` `:mod=` |
| Scheduling | `:no-overlap` `:no-overlap-2d` `:cumulative` |
| Enforcement | `[:only-if [literals] constraint]`, where a literal is `:b` or `[:not :b]` |

Declare intervals under `:intervals`, for example
`{:task {:start :s :size 3 :present :b}}`. Options are `:time-limit-s`, `:workers`, `:seed`,
`:relative-gap`, `:log?`, `:all-solutions?` and `:solution-limit`.

### Linear and mixed-integer

```clojure
(require '[or-tools.linear :as lp])

;; Floating-point results are close to, not equal to, the exact values:
;; here the raw objective is 33.99999999999999. The result also holds :duals and :reduced-costs.
(-> (lp/solve {:variables {:x {:lb 0} :y {:lb 0}}
               :constraints [[:<= [:+ :x [:* 2 :y]] 14]
                             [:>= [:* 3 :x] :y]
                             [:<= [:- :x :y] 2]]
               :objective [:maximize [:+ [:* 3 :x] [:* 4 :y]]]})
    (get-in [:ok :objective])
    Math/round)
;; => 34

(-> (lp/solve {:solver :scip
               :variables {:x {:lb 0 :integer? true}}
               :objective [:maximize :x]
               :constraints [[:between 0 :x 7]]})
    (get-in [:ok :objective])
    Math/round)
;; => 7
```

`:solver` defaults to `:glop`. It also accepts `:pdlp` `:clp` `:glpk` `:scip` `:cbc` `:sat`
`:bop` `:gurobi` `:cplex` `:xpress`. A backend missing from the build returns
`{:error :linear/solver-unavailable}`.

GLOP with presolve reports an unbounded model as `:infeasible`. This is native behaviour. To
tell the two apart, pass `{:solver-parameters "use_preprocessing: false"}` or use `:scip`.

### Flows, assignment, knapsack

Labels may be keywords, strings or integers.

```clojure
(require '[or-tools.flow :as flow]
         '[or-tools.assignment :as assignment]
         '[or-tools.knapsack :as knapsack])

;; :flows annotates each arc with :flow.
(-> (flow/max-flow {:source :s :sink :t
                    :arcs [{:from :s :to :a :capacity 3}
                           {:from :a :to :t :capacity 2}]})
    (get-in [:ok :value]))
;; => 2

(-> (flow/min-cost-flow {:supplies {:s 2 :t -2}
                         :arcs [{:from :s :to :t :capacity 5 :unit-cost 3}]})
    (get-in [:ok :cost]))
;; => 6

;; An absent pair is forbidden. Workers and tasks must be equal in number.
(-> (assignment/solve {:costs {:ana {:lathe 90 :press 76}
                               :bo {:lathe 35 :press 85}}})
    (get-in [:ok :total-cost]))
;; => 111

(-> (knapsack/solve {:capacities [10]
                     :items [{:id :a :value 60 :weights [5]}
                             {:id :b :value 50 :weights [4]}
                             {:id :c :value 40 :weights [6]}]})
    :ok
    (select-keys [:value :items]))
;; => {:value 110 :items [:a :b]}
```

### Routing

```clojure
(require '[or-tools.routing :as routing])

(routing/solve
 {:matrix [[0 2 9] [2 0 6] [9 6 0]]
  :vehicles 1
  :depot 0
  :dimensions [{:name :load :transit {:demands [0 1 1]} :capacity 5}
               {:name :time :transit {:matrix [[0 2 9] [2 0 6] [9 6 0]]}
                :capacity 60 :slack 30 :start-at-zero? false
                :windows {1 [5 20]}}]
  :droppable {2 1000}
  :search {:first-solution :path-cheapest-arc
           :metaheuristic :guided-local-search
           :time-limit-s 1}})
;; :routes holds {:vehicle :cost :stops [{:node :cumuls {...}}]}, plus :objective and :dropped
```

Dimensions cover capacity, time windows and distance limits. Other keys are `:starts`,
`:ends`, `:pickups-deliveries` and `:fixed-vehicle-cost`. Metaheuristics that never terminate
on their own are refused unless `:time-limit-s` or `:solution-limit` is set.

A window on a vehicle's start node limits departure only, so a round trip may return after
the depot window closes. A window on a separate end node limits arrival. Set
`:finalize-cumuls? true` on a dimension to minimize each route's start and end values.

### Bin packing and scheduling

Both compile to CP-SAT and accept its options.

```clojure
(require '[or-tools.bin-packing :as bins]
         '[or-tools.scheduling :as scheduling])

(-> (bins/solve {:capacity 10 :items {:a 6 :b 5 :c 4}})
    (get-in [:ok :bins-used]))
;; => 2

;; :machines holds a timetable per machine.
(-> (scheduling/solve {:jobs {:j0 [{:machine :m0 :duration 3} {:machine :m1 :duration 2}]
                              :j1 [{:machine :m1 :duration 4}]}})
    (get-in [:ok :makespan]))
;; => 6
```

## Guide examples

`examples/` reproduces worked examples of the
[OR-Tools guides](https://developers.google.com/optimization) as plain data. The examples are not
in the published jar, so clone this repository to run them. At a REPL started with `clj -M:examples`:

```clojure no-run
(require '[or-tools.examples :as ex])
(ex/answer :cryptarithm)
;; => 72
```

`or-tools.examples/documented` maps each example to the answer in the table, and the test suite
checks every one against the real solver. The table's values were compared with the guide pages
on 2026-11-02.

| Guide page | Example | Answer |
|---|---|---|
| [CP-SAT](https://developers.google.com/optimization/cp/cp_example) | `:cp-sat-primer` | 35 |
| [All solutions](https://developers.google.com/optimization/cp/cp_solver) | `:all-solutions` | 18 solutions |
| [Cryptarithmetic](https://developers.google.com/optimization/cp/cryptarithmetic) | `:cryptarithm` | 72 solutions |
| [N-queens](https://developers.google.com/optimization/cp/queens) | `:n-queens` | 92 solutions |
| [Employee scheduling](https://developers.google.com/optimization/scheduling/employee_scheduling) | `:nurse-rostering` | 13 requests met |
| [Linear program](https://developers.google.com/optimization/lp/lp_example) | `:linear-program` | 34 |
| [Integer program](https://developers.google.com/optimization/mip/mip_example) | `:integer-program` | 23 |
| [Integer program, arrays](https://developers.google.com/optimization/mip/mip_var_array) | `:integer-program-arrays` | 260 |
| [Maximum flow](https://developers.google.com/optimization/flow/maxflow) | `:max-flow` | 60 |
| [Minimum cost flow](https://developers.google.com/optimization/flow/mincostflow) | `:min-cost-flow` | 150 |
| [Assignment](https://developers.google.com/optimization/assignment/linear_assignment) | `:assignment` | 265 |
| [Assignment, forbidden pair](https://developers.google.com/optimization/assignment/linear_assignment) | `:assignment-forbidden-pair` | 276 |
| [Assignment, task sizes](https://developers.google.com/optimization/assignment/assignment_cp) | `:assignment-task-sizes` | 326 |
| [Assignment, teams](https://developers.google.com/optimization/assignment/assignment_teams) | `:assignment-teams` | 250 |
| [Assignment, allowed groups](https://developers.google.com/optimization/assignment/assignment_groups) | `:assignment-groups` | 239 |
| [Knapsack](https://developers.google.com/optimization/pack/knapsack) | `:knapsack` | 7534 |
| [Multiple knapsacks](https://developers.google.com/optimization/pack/multiple_knapsack) | `:multiple-knapsacks` | 395 |
| [Bin packing](https://developers.google.com/optimization/pack/bin_packing) | `:bin-packing` | 4 bins |
| [Job shop](https://developers.google.com/optimization/scheduling/job_shop) | `:job-shop` | makespan 11 |
| [Traveling salesperson](https://developers.google.com/optimization/routing/tsp) | `:traveling-salesperson` | 7293 |
| [Vehicle routing](https://developers.google.com/optimization/routing/vrp) | `:balanced-fleet` | longest route 1712 |
| [Capacity constraints](https://developers.google.com/optimization/routing/cvrp) | `:capacitated-fleet` | 6208 |
| [Dropping visits](https://developers.google.com/optimization/routing/penalties) | `:droppable-fleet` | 7936 |
| [Time windows](https://developers.google.com/optimization/routing/vrptw) | `:time-windowed-fleet` | 82 minutes |
| [Pickups and deliveries](https://developers.google.com/optimization/routing/pickup_delivery) | `:pickup-delivery-distance` | 6916 |

The routing search can beat a guide's printed answer. `:droppable-fleet` reproduces the guide with
a first solution only. With guided local search it finds a lower objective than the guide's 7936,
and a test checks that plan by hand. `:balanced-fleet` also stops at its first solution, which
matches the guide's output.

[Stigler diet](https://developers.google.com/optimization/lp/stigler_diet) is not reproduced,
because its table of 77 foods is too large to copy here. The
[depot resources](https://developers.google.com/optimization/routing/cvrptw_resources) routing
example is not reproduced either, because the wrapper does not expose vehicle intervals at a depot.

## Architecture

Each bounded context has the same strata, lowest first:

| Stratum | Namespace | Role |
|---|---|---|
| Kernel | `or-tools.kernel.*` | Shared value objects, linear forms, label indexing |
| Domain | `or-tools.<context>.model` | Schemas, pure promotion from problem to plan, decoding |
| Port | `or-tools.ports` | One role-sized protocol per context |
| Boundary | `or-tools.<context>.native` | The only code touching Java and native objects |
| Facade | `or-tools.<context>` | Collect, promote, call the port, decode |

Each facade reads a dynamic `*engine*` at call time, so a test or another backend can bind its
own port implementation without redefining vars.

## Development

```bash
clojure -M:test                  # full suite
clojure -T:build readme-examples # run this README's clojure blocks and check each ;; => claim
clj-kondo --lint src test examples
```

## Releasing

`.github/workflows/release.yml` tests, bumps the patch version, tags `v{VERSION}` and publishes
to Clojars with [hive-build](https://github.com/hive-agi/hive-build) when a push to `main`
changes `src`, `deps.edn` or `version.edn`. Other commits do not mint a version. A version that
never reached Clojars is released as it stands, so run the workflow again to retry a failed deploy.
`staging-gate.yml` runs the same suite on `staging` and on pull requests, and never publishes.
Coordinates live in `version.edn`, and the current version in `VERSION`.

Tests use `hive-test.trifecta/deftrifecta` (golden, property and mutation facets). Goldens live
in `test/golden`. `or-tools.gates-test` reads its universe from the source files and fails when
a function lacks a malli contract or a public pure function lacks a trifecta. Boundary
namespaces are exercised through their facades against the real solver.
