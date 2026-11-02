# Examples

The `examples/` directory holds worked problems as plain data. Each has a keyword, and
`or-tools.examples/documented` maps every keyword to its expected answer. The test suite solves
every one with the real solver and compares.

## Running them

The examples are not in the published jar. Clone the repository and start a REPL with the
examples alias:

```bash
git clone https://github.com/BuddhiLW/or-tools-clj.git
cd or-tools-clj
clj -M:examples
```

Then, at the REPL:

```clojure no-run
(require '[or-tools.examples :as ex])

(ex/answer :cryptarithm)
;; => 72

(ex/documented :cryptarithm)
;; => 72
```

`ex/answer` solves the named example and returns the value in the table below. Each model is a
public var in its namespace, for example `or-tools.examples.routing/capacitated-fleet`, so you
can read it, change it and pass it to the solver yourself.

## Where the expected values come from

Each namespace's docstrings name the source of its values.

- **Google's guide pages.** The examples in `or-tools.examples`, `or-tools.examples.assignment`,
  `or-tools.examples.routing` and `or-tools.examples.diet` reproduce the
  [OR-Tools guides](https://developers.google.com/optimization). Their values are the numbers
  those pages print, compared with the pages on 2026-11-02.
- **Textbooks and published references.** The Wyndor Glass production plan (Hillier and
  Lieberman, *Introduction to Operations Research*, chapter 3), the PuLP Whiskas blending case
  study, the Wikipedia sudoku, the count of 3x3 magic squares and OEIS A000085 for involutions.
- **Brute-force and independent checks.** The scheduling recipes, the routing cookbook, most
  puzzles and the remaining planning examples. Their tests compute the answer without the
  solver: by enumeration, a sweep, a plain Dijkstra, or dual prices checked in plain Clojure.

## Constraint programming and puzzles

| Solves | Keyword | Namespace | Answer | Shows | Source |
|---|---|---|---|---|---|
| CP-SAT primer: maximize a linear objective | `:cp-sat-primer` | `or-tools.examples` | 35 | `cp/solve`, linear constraints | Google CP-SAT guide |
| Count the solutions of x != y over three variables | `:all-solutions` | `or-tools.examples` | 18 | `solve-all` | Google CP-SAT guide |
| CP + IS + FUN = TRUE | `:cryptarithm` | `or-tools.examples` | 72 solutions | `:all-different`, `solve-all` | Google cryptarithmetic guide |
| Eight queens | `:n-queens` | `or-tools.examples` | 92 solutions | `:all-different` over expressions | Google N-queens guide |
| Sudoku | `:sudoku` | `or-tools.examples.puzzles` | the solved grid | `:all-different` per unit | Wikipedia Sudoku article |
| Sudoku solution count | `:sudoku-solution-count` | `or-tools.examples.puzzles` | 1 | `solve-all` | A proper sudoku has one solution |
| Fewest colours for the map of Australia | `:australia-chromatic-number` | `or-tools.examples.puzzles` | 3 | `:!=`, reading `:status` | Triangle argument, checked colouring |
| Two colours for Australia | `:australia-two-colours` | `or-tools.examples.puzzles` | `:infeasible` | infeasible as an ok status | Triangle argument |
| Count three-colourings of Australia | `:australia-three-colourings` | `or-tools.examples.puzzles` | 18 | `solve-all` | Brute force over 3^7 colourings |
| Count 3x3 magic squares | `:magic-square-count` | `or-tools.examples.puzzles` | 8 | `:all-different`, sums | Textbook count |
| Shortest tour of six cities | `:tour-length` | `or-tools.examples.puzzles` | 76 | `:circuit` | Brute force over every order |
| Count involutions of five elements | `:involutions-of-5` | `or-tools.examples.puzzles` | 26 | `:inverse` | OEIS A000085, brute force |

## Assignment

| Solves | Keyword | Namespace | Answer | Shows | Source |
|---|---|---|---|---|---|
| Four workers, four tasks | `:assignment` | `or-tools.examples.assignment` | 265 | `or-tools.assignment` | Google linear assignment guide |
| Same, one pair forbidden | `:assignment-forbidden-pair` | `or-tools.examples.assignment` | 276 | absent pair in `:costs` | Google linear assignment guide |
| Workers with a workload limit | `:assignment-task-sizes` | `or-tools.examples.assignment` | 326 | CP-SAT, `:exactly-one`, weighted `:<=` | Google assignment with task sizes guide |
| Teams with a task limit | `:assignment-teams` | `or-tools.examples.assignment` | 250 | CP-SAT, `:at-most-one` | Google assignment with teams guide |
| Only allowed pairs of workers | `:assignment-groups` | `or-tools.examples.assignment` | 239 | CP-SAT, `:allowed` | Google allowed groups guide |

## Scheduling and rostering

| Solves | Keyword | Namespace | Answer | Shows | Source |
|---|---|---|---|---|---|
| Nurse rostering, maximize requests met | `:nurse-rostering` | `or-tools.examples` | 13 | `:bool` grid, `:exactly-one`, `:at-most-one` | Google employee scheduling guide |
| Job shop, three jobs | `:job-shop` | `or-tools.examples` | makespan 11 | `or-tools.scheduling` | Google job shop guide |
| Fewest meeting rooms | `:meeting-rooms` | `or-tools.examples.scheduling-cookbook` | 3 | optional intervals, `:no-overlap`, `:only-if` | Sweep over the meetings |
| Project with a shared resource | `:project-makespan` | `or-tools.examples.scheduling-cookbook` | 11 | `:cumulative`, `:max=` | Best serial schedule over every order |
| Pack rectangles in a strip | `:packing-height` | `or-tools.examples.scheduling-cookbook` | 5 | `:no-overlap-2d` | Area bound plus a checked packing |
| Drain a tank without overflow | `:tank-drain-times` | `or-tools.examples.scheduling-cookbook` | 9 | `:reservoir` | Brute force over 11^3 drain times |
| Weekly rosters, at most two nights in a row | `:night-rosters` | `or-tools.examples.scheduling-cookbook` | 1892 | `:automaton`, `solve-all` | Brute force over 3^7 rosters |
| Best-paid roster with a day off | `:best-roster-pay` | `or-tools.examples.scheduling-cookbook` | 17 | `:automaton`, `:only-if` with `[:not ...]` | Brute force over the same rosters |
| Closest prices from two catalogues | `:closest-prices` | `or-tools.examples.scheduling-cookbook` | 2 | `{:domain ...}` variables, `:abs=` | Brute force over the pairs |

## Linear and integer planning

| Solves | Keyword | Namespace | Answer | Shows | Source |
|---|---|---|---|---|---|
| Linear program | `:linear-program` | `or-tools.examples` | 34 | `or-tools.linear`, GLOP | Google linear optimization guide |
| Integer program | `:integer-program` | `or-tools.examples` | 23 | `:scip`, `:integer? true` | Google mixed-integer guide |
| Integer program over arrays | `:integer-program-arrays` | `or-tools.examples` | 260 | constraints built with `mapv` | Google mixed-integer guide |
| Stigler diet, annual cost | `:stigler-annual-price` | `or-tools.examples.diet` | 39.6617 | 77 variables, `:>=` minimums | Google Stigler diet guide |
| Stigler diet, foods bought | `:stigler-foods` | `or-tools.examples.diet` | five foods | reading `:values` | Google Stigler diet guide |
| Ship from plants to markets | `:transportation` | `or-tools.examples.planning` | 585 | `:=` supply and demand | Dual prices checked in plain Clojure |
| Same, bound by the dual prices | `:transportation-dual-bound` | `or-tools.examples.planning` | 585 | `:duals` | Strong duality |
| Same, as a min-cost flow | `:transportation-flow` | `or-tools.examples.planning` | 585 | `or-tools.flow/min-cost-flow` | Agrees with the LP |
| Cheapest cat-food blend, in cents | `:whiskas-cents` | `or-tools.examples.planning` | 52 | blending, real coefficients | PuLP case study |
| Product mix on GLOP | `:production-plan-glop` | `or-tools.examples.planning` | 36 | continuous plan | Hillier and Lieberman, Wyndor Glass |
| Product mix on SCIP | `:production-plan-scip` | `or-tools.examples.planning` | 36 | same plan, whole batches | Hillier and Lieberman, enumerated |

## Flows, knapsack and bin packing

| Solves | Keyword | Namespace | Answer | Shows | Source |
|---|---|---|---|---|---|
| Maximum flow | `:max-flow` | `or-tools.examples` | 60 | `flow/max-flow` | Google maximum flow guide |
| Minimum cost flow | `:min-cost-flow` | `or-tools.examples` | 150 | `flow/min-cost-flow`, `:supplies` | Google minimum cost flow guide |
| Shortest path length | `:shortest-path-s-x` | `or-tools.examples.planning` | 9 | min-cost flow of one unit | Dijkstra and enumeration |
| Shortest path route | `:shortest-path-route` | `or-tools.examples.planning` | `[:s :y :t :x]` | reading `:flows` | Dijkstra and enumeration |
| Knapsack, fifty items | `:knapsack` | `or-tools.examples` | 7534 | `or-tools.knapsack` | Google knapsack guide |
| Weight and volume knapsack | `:cargo-value` | `or-tools.examples.planning` | 149 | two capacities | Enumeration of every subset |
| Multiple knapsacks | `:multiple-knapsacks` | `or-tools.examples` | 395 | CP-SAT, `:at-most-one` | Google multiple knapsacks guide |
| Bin packing | `:bin-packing` | `or-tools.examples` | 4 bins | `or-tools.bin-packing` | Google bin packing guide |

## Vehicle routing

| Solves | Keyword | Namespace | Answer | Shows | Source |
|---|---|---|---|---|---|
| Traveling salesperson, 13 cities | `:traveling-salesperson` | `or-tools.examples.routing` | 7293 | `:first-solution` | Google TSP guide |
| Four vehicles, balanced | `:balanced-fleet` | `or-tools.examples.routing` | longest route 1712 | `:global-span-coefficient` | Google vehicle routing guide |
| Capacity limits | `:capacitated-fleet` | `or-tools.examples.routing` | 6208 | `{:demands ...}`, guided local search | Google capacity constraints guide |
| Dropping visits | `:droppable-fleet` | `or-tools.examples.routing` | 7936 | `:droppable` | Google dropping visits guide |
| Time windows | `:time-windowed-fleet` | `or-tools.examples.routing` | 82 minutes | `:windows`, `:slack`, `:finalize-cumuls?` | Google time windows guide |
| Pickups and deliveries, pairs honoured | `:pickup-delivery-fleet` | `or-tools.examples.routing` | `true` | `:pickups-deliveries` | Checked from the routes, not a guide number |
| Pickups and deliveries, distance | `:pickup-delivery-distance` | `or-tools.examples.routing` | 6916 | `:pickups-deliveries` | Google pickups and deliveries guide |
| Vehicles with their own start and end | `:separate-ends-cost` | `or-tools.examples.routing-cookbook` | 26 | `:starts`, `:ends` | Enumeration of every split and order |
| Two free vehicles, total cost | `:free-fleet-cost` | `or-tools.examples.routing-cookbook` | 34 | own depots | Enumeration |
| Two free vehicles, vehicles used | `:free-fleet-vehicles` | `or-tools.examples.routing-cookbook` | 2 | own depots | Enumeration |
| Vehicles costing 100 each, total cost | `:costly-fleet-cost` | `or-tools.examples.routing-cookbook` | 148 | `:fixed-vehicle-cost` | Enumeration |
| Vehicles costing 100 each, vehicles used | `:costly-fleet-vehicles` | `or-tools.examples.routing-cookbook` | 1 | `:fixed-vehicle-cost` | Enumeration |

### When the search beats the guide

Routing search is heuristic, and a longer search can find a better plan than the one a guide
prints. `:droppable-fleet` reproduces the guide with a first solution only. With guided local
search it finds a lower objective than the guide's 7936, and a test checks that plan by hand.
`:balanced-fleet` also stops at its first solution, which matches the guide's output. So a value
in this table that comes from a guide is the guide's answer under the guide's search settings,
not always the best possible plan.

## Walkthrough: pick meeting rooms

Three meetings run at hours 0 to 3, 2 to 5 and 4 to 6, and there are two rooms. Each meeting
gets one optional interval per room. The `:present` literal says whether the meeting is in that
room, and `:no-overlap` ignores intervals that are absent. A room counts as used unless all its
literals are false.

```clojure
(require '[or-tools.cp-sat :as cp])

(def rooms-model
  {:variables {:a-in-r1 :bool :a-in-r2 :bool
               :b-in-r1 :bool :b-in-r2 :bool
               :c-in-r1 :bool :c-in-r2 :bool
               :r1-used :bool :r2-used :bool}
   :intervals {:a-r1 {:start 0 :size 3 :present :a-in-r1}
               :a-r2 {:start 0 :size 3 :present :a-in-r2}
               :b-r1 {:start 2 :size 3 :present :b-in-r1}
               :b-r2 {:start 2 :size 3 :present :b-in-r2}
               :c-r1 {:start 4 :size 2 :present :c-in-r1}
               :c-r2 {:start 4 :size 2 :present :c-in-r2}}
   :constraints [[:exactly-one [:a-in-r1 :a-in-r2]]
                 [:exactly-one [:b-in-r1 :b-in-r2]]
                 [:exactly-one [:c-in-r1 :c-in-r2]]
                 [:no-overlap [:a-r1 :b-r1 :c-r1]]
                 [:no-overlap [:a-r2 :b-r2 :c-r2]]
                 [:only-if [[:not :r1-used]] [:= [:+ :a-in-r1 :b-in-r1 :c-in-r1] 0]]
                 [:only-if [[:not :r2-used]] [:= [:+ :a-in-r2 :b-in-r2 :c-in-r2] 0]]]
   :objective [:minimize [:+ :r1-used :r2-used]]})

(-> (cp/solve rooms-model) :ok (select-keys [:status :objective]))
;; => {:status :optimal :objective 2.0}

(let [v (get-in (cp/solve rooms-model) [:ok :values])]
  [(= (:a-in-r1 v) (:c-in-r1 v)) (= (:a-in-r1 v) (:b-in-r1 v))])
;; => [true false]
```

Meetings a and b overlap, so they need different rooms. Meeting c starts when a has ended, so it
shares a's room. `:meeting-rooms` is the same model for six meetings and four rooms, built with
`for`.

## Walkthrough: the Stigler diet shape

The Stigler diet asks for the cheapest daily spending on foods that meets every nutrient
minimum. Each variable is the dollars spent on one food, and each constraint sums one nutrient
over the foods. This is three of the guide's 77 foods and three of its nine nutrients, with the
guide's figures per dollar: calories, protein and vitamin C.

```clojure
(require '[or-tools.linear :as lp])

(def small-diet
  {:variables {:flour {:lb 0} :liver {:lb 0} :cabbage {:lb 0}}
   :constraints [[:>= [:+ [:* 44.7 :flour] [:* 2.2 :liver] [:* 2.6 :cabbage]] 3]
                 [:>= [:+ [:* 1411 :flour] [:* 333 :liver] [:* 125 :cabbage]] 70]
                 [:>= [:+ [:* 525 :liver] [:* 5369 :cabbage]] 75]]
   :objective [:minimize [:+ :flour :liver :cabbage]]})

(let [{:keys [status objective values]} (:ok (lp/solve small-diet))]
  {:status status
   :cents-per-day (Math/round (* 100 objective))
   :foods (set (keep (fn [[food dollars]] (when (> dollars 1e-9) food)) values))})
;; => {:status :optimal :cents-per-day 8 :foods #{:flour :cabbage}}
```

The full problem is `or-tools.examples.diet/stigler-problem`, built from the guide's table with
`map-indexed`. `(answer :stigler-annual-price)` multiplies its daily cost by 365.

## Walkthrough: a fleet with a fixed vehicle cost

Two vehicles are based at nodes 0 and 1, far apart. Customer 2 is near node 0 and customer 3
near node 1. When vehicles are free each serves its own neighbour. When each vehicle used costs
100, one vehicle serves both customers and the other stays home.

```clojure
(require '[or-tools.routing :as routing])

(def fleet
  {:matrix [[0 20 3 21]
            [20 0 21 3]
            [3 21 0 18]
            [21 3 18 0]]
   :starts [0 1]
   :ends [0 1]
   :search {:first-solution :parallel-cheapest-insertion}})

(defn vehicles-used [result]
  (count (filter #(> (count (:stops %)) 2) (get-in result [:ok :routes]))))

(let [result (routing/solve fleet)]
  [(get-in result [:ok :objective]) (vehicles-used result)])
;; => [12 2]

(let [result (routing/solve (assoc fleet :fixed-vehicle-cost 100))]
  [(get-in result [:ok :objective]) (vehicles-used result)])
;; => [142 1]
```

With the fixed cost the objective is 100 for the one vehicle plus 3 + 18 + 21 for its tour. A
route with only two stops is a vehicle that stayed at its depot. `:costly-fleet-cost` is the
same idea with six nodes, and its test proves the optimum by enumeration.
