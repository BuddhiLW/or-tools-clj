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

Add `io.github.buddhilw/or-tools-clj` to `deps.edn` at the version shown on the badge. The
JVM flag silences the native-access warning on recent JDKs.

```edn
{:deps {io.github.buddhilw/or-tools-clj {:mvn/version "<version>"}}
 :aliases {:dev {:jvm-opts ["--enable-native-access=ALL-UNNAMED"]}}}
```

The native library loads on the first solve.

## Examples

### CP-SAT

```clojure
(require '[or-tools.cp-sat :as cp])

(cp/solve {:variables {:x [0 10] :y [0 10]}
           :constraints [[:<= [:+ :x :y] 12]]
           :objective [:maximize [:+ :x [:* 2 :y]]]})
;; {:ok {:status :optimal :objective 22.0 :best-bound 22.0
;;       :values {:x 2 :y 10} :stats {...}}}

(cp/solve-all {:variables {:a [0 1] :b [0 1]}
               :constraints [[:!= :a :b]]})
;; :solutions holds [{:a 0 :b 1} {:a 1 :b 0}] in solver order
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

(lp/solve {:variables {:x {:lb 0} :y {:lb 0}}
           :constraints [[:<= [:+ :x [:* 2 :y]] 14]
                         [:>= [:* 3 :x] :y]
                         [:<= [:- :x :y] 2]]
           :objective [:maximize [:+ [:* 3 :x] [:* 4 :y]]]})
;; :objective 34.0, :values {:x 6.0 :y 4.0}, plus :duals and :reduced-costs

(lp/solve {:solver :scip
           :variables {:x {:lb 0 :integer? true}}
           :objective [:maximize :x]
           :constraints [[:between 0 :x 7]]})
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

(flow/max-flow {:source :s :sink :t
                :arcs [{:from :s :to :a :capacity 3}
                       {:from :a :to :t :capacity 2}]})
;; :value 2, and :flows annotates each arc with :flow

(flow/min-cost-flow {:supplies {:s 2 :t -2}
                     :arcs [{:from :s :to :t :capacity 5 :unit-cost 3}]})
;; :cost 6

(assignment/solve {:costs {:ana {:lathe 90 :press 76}
                           :bo {:lathe 35 :press 85}}})
;; An absent pair is forbidden. Workers and tasks must be equal in number.

(knapsack/solve {:capacities [10]
                 :items [{:id :a :value 60 :weights [5]}
                         {:id :b :value 50 :weights [4]}
                         {:id :c :value 40 :weights [6]}]})
;; {:ok {:status :optimal :value 110 :items [:a :b]}}
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

(bins/solve {:capacity 10 :items {:a 6 :b 5 :c 4}})
;; :bins-used 2

(scheduling/solve {:jobs {:j0 [{:machine :m0 :duration 3} {:machine :m1 :duration 2}]
                          :j1 [{:machine :m1 :duration 4}]}})
;; :makespan, and :machines holds a timetable per machine
```

## Guide examples

`examples/or_tools/examples.clj` reproduces the worked examples of the OR-Tools guides as plain
data, including cryptarithm, nurse rostering, array MIP, 50-item knapsack, VRP, capacity limits,
time windows, and pickups and deliveries.

```bash
clojure -M:examples -e "(require '[or-tools.examples :as ex]) (ex/answer :cryptarithm)"
```

`or-tools.examples/documented` maps each example to the answer its guide reports, and the test
suite checks every one against the real solver.

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
clojure -M:test        # full suite
clj-kondo --lint src test
```

## Releasing

`.github/workflows/release.yml` tests, bumps the patch version, tags `v{VERSION}` and publishes
to Clojars with [hive-build](https://github.com/hive-agi/hive-build) when a push to `main`
changes `src`, `deps.edn` or `version.edn`. Other commits do not mint a version.
`staging-gate.yml` runs the same suite on `staging` and on pull requests, and never publishes.
Coordinates live in `version.edn`, and the current version in `VERSION`.

Tests use `hive-test.trifecta/deftrifecta` (golden, property and mutation facets). Goldens live
in `test/golden`. `or-tools.gates-test` reads its universe from the source files and fails when
a function lacks a malli contract or a public pure function lacks a trifecta. Boundary
namespaces are exercised through their facades against the real solver.
