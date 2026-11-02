(ns or-tools.examples
  "Worked examples solved through this wrapper: the registry of every example.

   (answer :cryptarithm) => 72
   (keys documented)     lists every example with its expected answer.

   Guide examples without a namespace of their own live here. The others are
   or-tools.examples.assignment, .routing, .diet, .planning, .puzzles,
   .scheduling-cookbook and .routing-cookbook."
  (:require [malli.core :as m]
            [or-tools.bin-packing :as bin-packing]
            [or-tools.cp-sat :as cp-sat]
            [or-tools.examples.assignment :as assignment-examples]
            [or-tools.examples.diet :as diet]
            [or-tools.examples.planning :as planning]
            [or-tools.examples.puzzles :as puzzles]
            [or-tools.examples.routing :as routing-examples]
            [or-tools.examples.routing-cookbook :as routing-cookbook]
            [or-tools.examples.scheduling-cookbook :as scheduling-cookbook]
            [or-tools.flow :as flow]
            [or-tools.knapsack :as knapsack]
            [or-tools.linear :as linear]
            [or-tools.scheduling :as scheduling]))

(defn- ok-at
  "Read `path` from the ok side of `result`, or return its error category."
  [result path]
  (or (:error result) (get-in result (into [:ok] path))))

(m/=> ok-at [:=> [:cat map? [:vector :keyword]] :any])

;; Constraint optimization

(def cp-sat-primer
  "CP-SAT guide, optimization example."
  {:variables {:x [0 50] :y [0 50] :z [0 50]}
   :constraints [[:<= [:+ [:* 2 :x] [:* 7 :y] [:* 3 :z]] 50]
                 [:<= [:+ [:* 3 :x] [:* -5 :y] [:* 7 :z]] 45]
                 [:<= [:+ [:* 5 :x] [:* 2 :y] [:* -6 :z]] 37]]
   :objective [:maximize [:+ [:* 2 :x] [:* 2 :y] [:* 3 :z]]]})

(def three-variables
  "CP-SAT guide, all solutions: three variables with x different from y."
  {:variables {:x [0 2] :y [0 2] :z [0 2]}
   :constraints [[:!= :x :y]]})

(def cryptarithm
  "Cryptarithmetic guide: CP + IS + FUN = TRUE in base 10."
  (let [letters [:c :p :i :s :f :u :n :t :r :e]
        leading #{:c :i :f :t}]
    {:variables (into {}
                      (map (fn [letter] [letter (if (leading letter) [1 9] [0 9])]))
                      letters)
     :constraints [[:all-different letters]
                   [:= [:+ [:* 10 :c] :p [:* 10 :i] :s [:* 100 :f] [:* 10 :u] :n]
                    [:+ [:* 1000 :t] [:* 100 :r] [:* 10 :u] :e]]]}))

(defn- n-queens
  "N-queens guide: place `n` queens so that none attack each other."
  [n]
  (let [rows (mapv (fn [column] (keyword (str "q" column))) (range n))]
    {:variables (zipmap rows (repeat [0 (dec n)]))
     :constraints [[:all-different rows]
                   [:all-different (vec (map-indexed (fn [i row] [:+ row i]) rows))]
                   [:all-different (vec (map-indexed (fn [i row] [:- row i]) rows))]]}))

(m/=> n-queens [:=> [:cat [:int {:min 1}]] map?])

(def nurse-requests
  "Employee scheduling guide: requests per nurse, day and shift."
  [[[0 0 1] [0 0 0] [0 0 0] [0 0 0] [0 0 1] [0 1 0] [0 0 1]]
   [[0 0 0] [0 0 0] [0 1 0] [0 1 0] [1 0 0] [0 0 0] [0 0 1]]
   [[0 1 0] [0 1 0] [0 0 0] [1 0 0] [0 0 0] [0 1 0] [0 0 0]]
   [[0 0 1] [0 0 0] [1 0 0] [0 1 0] [0 0 0] [1 0 0] [0 0 0]]
   [[0 0 0] [0 0 1] [1 0 0] [0 0 0] [1 0 0] [0 1 0] [0 0 0]]])

(defn- shift
  "Name the variable assigning `nurse` to `shift-number` on `day`."
  [nurse day shift-number]
  (keyword "shift" (str "nurse" nurse "-day" day "-shift" shift-number)))

(m/=> shift [:=> [:cat :int :int :int] :keyword])

(defn- nurse-rostering
  "Employee scheduling guide: maximize the requests met, sharing shifts fairly."
  []
  (let [nurses (range 5)
        days (range 7)
        shifts (range 3)
        all-of (fn [nurse] (for [d days s shifts] (shift nurse d s)))]
    {:variables (into {} (for [n nurses d days s shifts] [(shift n d s) :bool]))
     :constraints (vec (concat
                        (for [d days s shifts]
                          [:exactly-one (mapv (fn [n] (shift n d s)) nurses)])
                        (for [n nurses d days]
                          [:at-most-one (mapv (fn [s] (shift n d s)) shifts)])
                        (for [n nurses]
                          [:>= (into [:+] (all-of n)) 4])
                        (for [n nurses]
                          [:<= (into [:+] (all-of n)) 5])))
     :objective [:maximize
                 (into [:+]
                       (for [n nurses d days s shifts
                             :let [wanted (get-in nurse-requests [n d s])]
                             :when (pos? wanted)]
                         (shift n d s)))]}))

(m/=> nurse-rostering [:=> [:cat] map?])

;; Linear and integer optimization

(def linear-program
  "Linear optimization guide."
  {:variables {:x {:lb 0} :y {:lb 0}}
   :constraints [[:<= [:+ :x [:* 2 :y]] 14]
                 [:>= [:* 3 :x] :y]
                 [:<= [:- :x :y] 2]]
   :objective [:maximize [:+ [:* 3 :x] [:* 4 :y]]]})

(def integer-program
  "Mixed-integer guide, first example."
  {:solver :scip
   :variables {:x {:lb 0 :integer? true} :y {:lb 0 :integer? true}}
   :constraints [[:<= [:+ :x [:* 7 :y]] 17.5]
                 [:<= :x 3.5]]
   :objective [:maximize [:+ :x [:* 10 :y]]]})

(def integer-program-arrays
  "Mixed-integer guide, array formulation."
  (let [variables [:v0 :v1 :v2 :v3 :v4]
        weighted (fn [coefficients] (into [:+] (map (fn [c v] [:* c v]) coefficients variables)))]
    {:solver :scip
     :variables (zipmap variables (repeat {:lb 0 :integer? true}))
     :constraints (mapv (fn [[coefficients bound]] [:<= (weighted coefficients) bound])
                        [[[5 7 9 2 1] 250]
                         [[18 4 -9 10 12] 285]
                         [[4 7 3 8 5] 211]
                         [[5 13 16 3 -7] 315]])
     :objective [:maximize (weighted [7 8 2 9 6])]}))

;; Network flows

(def max-flow-network
  "Maximum flows guide."
  {:source 0
   :sink 4
   :arcs (mapv (fn [[from to capacity]] {:from from :to to :capacity capacity})
               [[0 1 20] [0 2 30] [0 3 10] [1 2 40] [1 4 30]
                [2 3 10] [2 4 20] [3 2 5] [3 4 20]])})

(def min-cost-network
  "Minimum cost flows guide."
  {:supplies {0 20 3 -5 4 -15}
   :arcs (mapv (fn [[from to capacity unit-cost]]
                 {:from from :to to :capacity capacity :unit-cost unit-cost})
               [[0 1 15 4] [0 2 8 4] [1 2 20 2] [1 3 4 2] [1 4 10 6]
                [2 3 15 1] [2 4 4 3] [3 4 20 2] [4 2 5 3]])})

;; Knapsack and bin packing

(def fifty-items
  "Knapsack guide."
  {:capacities [850]
   :items (mapv (fn [id value weight] {:id id :value value :weights [weight]})
                (range)
                [360 83 59 130 431 67 230 52 93 125 670 892 600 38 48 147 78 256 63 17
                 120 164 432 35 92 110 22 42 50 323 514 28 87 73 78 15 26 78 210 36
                 85 189 274 43 33 10 19 389 276 312]
                [7 0 30 22 80 94 11 81 70 64 59 18 0 36 3 8 15 42 9 0
                 42 47 52 32 26 48 55 6 29 84 2 4 18 56 7 29 93 44 71 3
                 86 66 31 65 0 79 20 65 52 13])})

(def packing-items
  "Multiple knapsacks guide: fifteen items and five bins of equal capacity."
  {:weights [48 30 42 36 36 48 42 42 36 24 30 30 42 36 36]
   :values [10 30 25 50 35 30 15 40 30 35 45 10 20 30 25]
   :bins 5
   :capacity 100})

(defn- placed
  "Name the variable placing `item` in `bin`."
  [item bin]
  (keyword "placed" (str "item" item "-bin" bin)))

(m/=> placed [:=> [:cat :int :int] :keyword])

(defn- multiple-knapsacks
  "Multiple knapsacks guide: maximize the value packed without overfilling a bin."
  []
  (let [{:keys [weights values bins capacity]} packing-items
        items (range (count weights))
        all-bins (range bins)]
    {:variables (into {} (for [i items b all-bins] [(placed i b) :bool]))
     :constraints (vec (concat
                        (for [i items]
                          [:at-most-one (mapv (partial placed i) all-bins)])
                        (for [b all-bins]
                          [:<= (into [:+] (for [i items] [:* (weights i) (placed i b)]))
                           capacity])))
     :objective [:maximize
                 (into [:+] (for [i items b all-bins] [:* (values i) (placed i b)]))]}))

(m/=> multiple-knapsacks [:=> [:cat] map?])

(def eleven-items
  "Bin packing guide."
  {:capacity 100
   :items (zipmap (map (fn [i] (keyword (str "item" i))) (range))
                  [48 30 19 36 36 27 42 42 36 24 30])})

;; Scheduling

(def three-jobs
  "Job shop guide."
  {:jobs {:j0 [{:machine 0 :duration 3} {:machine 1 :duration 2} {:machine 2 :duration 2}]
          :j1 [{:machine 0 :duration 2} {:machine 2 :duration 1} {:machine 1 :duration 4}]
          :j2 [{:machine 1 :duration 4} {:machine 2 :duration 3}]}})

;; The registry of examples

(def ^:private sections
  "Every namespace of examples, as its documented answers and the function solving them."
  [[assignment-examples/documented assignment-examples/answer]
   [routing-examples/documented routing-examples/answer]
   [puzzles/documented puzzles/answer]
   [scheduling-cookbook/documented scheduling-cookbook/answer]
   [diet/documented diet/answer]
   [planning/documented planning/answer]
   [routing-cookbook/documented routing-cookbook/answer]])

(def documented
  "Every example and its expected answer. Each namespace names the source of its values."
  (into {:cp-sat-primer 35
         :all-solutions 18
         :cryptarithm 72
         :n-queens 92
         :nurse-rostering 13
         :linear-program 34
         :integer-program 23
         :integer-program-arrays 260
         :max-flow 60
         :min-cost-flow 150
         :knapsack 7534
         :multiple-knapsacks 395
         :bin-packing 4
         :job-shop 11}
        (mapcat first)
        sections))

(def Example
  "An example name."
  (into [:enum] (keys documented)))

(defn- whole
  "Round a solver's floating objective to the nearest integer. Pass errors through."
  [value]
  (if (number? value) (Math/round (double value)) value))

(m/=> whole [:=> [:cat :any] :any])

(defn answer
  "Solve the example named `example` and return its expected answer."
  [example]
  (if-let [solve (some (fn [[answers solve]] (when (contains? answers example) solve))
                       sections)]
    (solve example)
    (case example
      :cp-sat-primer (whole (ok-at (cp-sat/solve cp-sat-primer) [:objective]))
      :all-solutions (count (ok-at (cp-sat/solve-all three-variables {:workers 1}) [:solutions]))
      :cryptarithm (count (ok-at (cp-sat/solve-all cryptarithm {:workers 1}) [:solutions]))
      :n-queens (count (ok-at (cp-sat/solve-all (n-queens 8) {:workers 1}) [:solutions]))
      :nurse-rostering (whole (ok-at (cp-sat/solve (nurse-rostering)) [:objective]))
      :linear-program (whole (ok-at (linear/solve linear-program) [:objective]))
      :integer-program (whole (ok-at (linear/solve integer-program) [:objective]))
      :integer-program-arrays (whole (ok-at (linear/solve integer-program-arrays) [:objective]))
      :max-flow (ok-at (flow/max-flow max-flow-network) [:value])
      :min-cost-flow (ok-at (flow/min-cost-flow min-cost-network) [:cost])
      :knapsack (ok-at (knapsack/solve fifty-items) [:value])
      :multiple-knapsacks (whole (ok-at (cp-sat/solve (multiple-knapsacks)) [:objective]))
      :bin-packing (ok-at (bin-packing/solve eleven-items {:time-limit-s 30}) [:bins-used])
      :job-shop (ok-at (scheduling/solve three-jobs) [:makespan]))))

(m/=> answer [:=> [:cat Example] :any])
