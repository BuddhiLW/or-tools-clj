# or-tools-clj

Solve assignment, routing, scheduling and planning problems from Clojure with
[Google OR-Tools](https://developers.google.com/optimization) 9.15. Problems and answers are plain data.

[![Clojars Project](https://img.shields.io/clojars/v/io.github.buddhilw/or-tools-clj.svg)](https://clojars.org/io.github.buddhilw/or-tools-clj)
[![cljdoc](https://cljdoc.org/badge/io.github.buddhilw/or-tools-clj)](https://cljdoc.org/d/io.github.buddhilw/or-tools-clj)
[![release](https://github.com/BuddhiLW/or-tools-clj/actions/workflows/release.yml/badge.svg)](https://github.com/BuddhiLW/or-tools-clj/actions/workflows/release.yml)
[![License: EPL-1.0](https://img.shields.io/badge/license-EPL--1.0-blue.svg)](https://www.eclipse.org/legal/epl-v10.html)

## What it solves

| You want to | Namespace | Example to read |
|---|---|---|
| assign people to tasks, one each, at least cost | `or-tools.assignment` | `:assignment` |
| assign people to tasks with extra rules (teams, workload limits) | `or-tools.cp-sat` | `:assignment-teams` |
| plan delivery routes for one or more vehicles | `or-tools.routing` | `:capacitated-fleet` |
| visit customers inside their opening hours | `or-tools.routing` | `:time-windowed-fleet` |
| build a timetable of jobs on machines | `or-tools.scheduling` | `:job-shop` |
| build a staff roster or book meeting rooms | `or-tools.cp-sat` | `:nurse-rostering`, `:meeting-rooms` |
| choose how much of each thing to make or buy | `or-tools.linear` | `:production-plan-glop` |
| find the cheapest mix that meets minimums (diet, blend) | `or-tools.linear` | `:stigler-annual-price` |
| pick the most valuable items that fit a budget or a bag | `or-tools.knapsack` | `:knapsack` |
| pack items into as few boxes as possible | `or-tools.bin-packing` | `:bin-packing` |
| ship goods through a network, or find a shortest path | `or-tools.flow` | `:min-cost-flow`, `:shortest-path-s-x` |
| solve a puzzle or any yes/no rule system | `or-tools.cp-sat` | `:sudoku` |

Each keyword names a worked example. [doc/examples.md](doc/examples.md) lists them all.

## Install

```edn
{:deps {io.github.buddhilw/or-tools-clj {:mvn/version "0.1.1"}}
 :aliases {:dev {:jvm-opts ["--enable-native-access=ALL-UNNAMED"]}}}
```

The OR-Tools jar carries the native library, which loads on the first solve. The JVM flag
silences the native-access warning on recent JDKs. The test suite runs on Linux x86-64.

## A first model

A bakery sells bread at 3 and cake at 8. Bread takes 2 hours of oven time and cake 4, the oven
has 40 hours, and there are 14 trays. How many of each should it bake?

```clojure
(require '[or-tools.cp-sat :as cp])

(-> (cp/solve {:variables {:bread [0 100] :cake [0 100]}
               :constraints [[:<= [:+ [:* 2 :bread] [:* 4 :cake]] 40]
                             [:<= [:+ :bread :cake] 14]]
               :objective [:maximize [:+ [:* 3 :bread] [:* 8 :cake]]]})
    :ok
    (select-keys [:status :objective :values]))
;; => {:status :optimal :objective 80.0 :values {:bread 0 :cake 10}}
```

A model names its variables with their ranges, lists constraints as vectors, and states what
to maximize or minimize. An expression is a number, a variable, `[:+ ...]`, `[:- ...]` or
`[:* coefficient expression]`.

## How results work

Every call returns `{:ok solution}` or `{:error category ...}`.

`:error` means the input was invalid or the native call failed. The category says which.

```clojure
(require '[or-tools.cp-sat :as cp])

(cp/solve {:variables {:x [0 3]} :constraints [[:> :y 5]]})
;; => {:error :cp-sat/unknown-reference :unknown {:variables #{:y} :intervals #{}}}
```

A model with no solution is not an error. It is an ok result whose `:status` is `:infeasible`.
Check `:status` before you read `:values`.

```clojure
(require '[or-tools.cp-sat :as cp])

(get-in (cp/solve {:variables {:x [0 3]} :constraints [[:> :x 5]]}) [:ok :status])
;; => :infeasible
```

The linear solvers work in floating point, so a result is close to the exact value but may not
equal it. Round before you compare.

```clojure
(require '[or-tools.linear :as lp])

(-> (lp/solve {:variables {:x {:lb 0} :y {:lb 0}}
               :constraints [[:<= [:+ :x [:* 2 :y]] 14]
                             [:>= [:* 3 :x] :y]
                             [:<= [:- :x :y] 2]]
               :objective [:maximize [:+ [:* 3 :x] [:* 4 :y]]]})
    (get-in [:ok :objective])
    Math/round)
;; => 34
```

The raw objective here is 33.99999999999999.

## Choosing a solver

- **CP-SAT** (`or-tools.cp-sat`) works on whole numbers and yes/no choices. Use it for puzzles,
  rosters, timetables, assignments with side rules, and anything with "if this then that",
  "all different" or "these must not overlap". `or-tools.scheduling` and
  `or-tools.bin-packing` build CP-SAT models for you.
- **Linear** (`or-tools.linear`) works on quantities that may be fractional: how much to make,
  buy or ship. It also reports dual prices. Mark variables `:integer? true` and pick `:scip`,
  `:cbc` or `:sat` when the amounts must be whole.
- **Routing** (`or-tools.routing`) plans vehicle tours over a distance matrix, with loads,
  time windows and optional visits. It searches heuristically, so its answer is usually good
  but not proved best.
- **Flow, assignment and knapsack** are specialised solvers for their one problem shape. Use
  them when the problem fits as is.

## Coverage of the OR-Tools guides

| Guide | Namespace | Entry points | Example |
|---|---|---|---|
| [Constraint optimization](https://developers.google.com/optimization/cp) | `or-tools.cp-sat` | `solve`, `solve-all` | `:cp-sat-primer` |
| [Linear optimization](https://developers.google.com/optimization/lp) | `or-tools.linear` | `solve` | `:linear-program` |
| [Mixed-integer optimization](https://developers.google.com/optimization/mip) | `or-tools.linear` | `solve` | `:integer-program` |
| [Network flows](https://developers.google.com/optimization/flow) | `or-tools.flow` | `max-flow`, `min-cost-flow` | `:max-flow` |
| [Assignment](https://developers.google.com/optimization/assignment) | `or-tools.assignment`, `or-tools.cp-sat` | `solve` | `:assignment` |
| [Knapsack](https://developers.google.com/optimization/pack/knapsack) | `or-tools.knapsack` | `solve` | `:knapsack` |
| [Bin packing](https://developers.google.com/optimization/pack/bin_packing) | `or-tools.bin-packing` | `solve` | `:bin-packing` |
| [Scheduling](https://developers.google.com/optimization/scheduling) | `or-tools.scheduling`, `or-tools.cp-sat` | `solve` | `:job-shop` |
| [Vehicle routing](https://developers.google.com/optimization/routing) | `or-tools.routing` | `solve` | `:traveling-salesperson` |

The [depot resources](https://developers.google.com/optimization/routing/cvrptw_resources)
routing example is not reproduced, because the wrapper does not expose vehicle intervals at a
depot.

## Further reading

- [doc/reference.md](doc/reference.md): every variable form, constraint, option and result
  field, per namespace.
- [doc/examples.md](doc/examples.md): every worked example, where its expected answer comes
  from, how to run it, and three walkthroughs.

## Architecture

Each problem family has the same layers, lowest first:

| Layer | Namespace | Role |
|---|---|---|
| Kernel | `or-tools.kernel.*` | Shared schemas, linear forms, label indexing |
| Domain | `or-tools.<family>.model` | Schemas, pure translation from problem to plan, decoding |
| Port | `or-tools.ports` | One protocol per family |
| Boundary | `or-tools.<family>.native` | The only code that touches Java and native objects |
| Facade | `or-tools.<family>` | Validate, translate, call the port, decode |

Each facade reads a dynamic `*engine*` at call time, so a test can bind its own port
implementation.

## Development

```bash
clojure -M:test                  # full suite against the real solver
clojure -T:build readme-examples # run this README's clojure blocks and check each ;; => claim
clojure -T:build readme-examples :readme '"doc/reference.md"'
clojure -T:build readme-examples :readme '"doc/examples.md"'
clj-kondo --lint src test examples
```

Tests use `hive-test.trifecta/deftrifecta` (golden, property and mutation facets). Goldens live
in `test/golden`. `or-tools.gates-test` fails when a function lacks a malli contract or a
public pure function lacks a trifecta.

## Releasing

`.github/workflows/release.yml` tests, checks the claims in the docs, bumps the patch version,
tags `v{VERSION}` and publishes to Clojars with [hive-build](https://github.com/hive-agi/hive-build)
when a push to `main` changes `src`, `deps.edn` or `version.edn`. Other commits do not mint a
version. A version that never reached Clojars is released as it stands, so run the workflow
again to retry a failed deploy. `staging-gate.yml` runs the same suite on `staging` and on pull
requests, and never publishes. Coordinates live in `version.edn` and the current version in
`VERSION`.
