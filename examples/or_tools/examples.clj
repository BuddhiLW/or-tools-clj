(ns or-tools.examples
  "The worked examples of the OR-Tools guides, solved through this wrapper.

   (answer :cryptarithm) => 72
   (keys documented)     lists every example with the answer its guide reports."
  (:require [malli.core :as m]
            [or-tools.assignment :as assignment]
            [or-tools.bin-packing :as bin-packing]
            [or-tools.cp-sat :as cp-sat]
            [or-tools.flow :as flow]
            [or-tools.knapsack :as knapsack]
            [or-tools.linear :as linear]
            [or-tools.routing :as routing]
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

;; Network flows and assignment

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

(def worker-costs
  "Linear sum assignment guide."
  {:costs (into {}
                (map-indexed (fn [worker row]
                               [worker (into {} (map-indexed vector) row)]))
                [[90 76 75 70] [35 85 55 65] [125 95 90 105] [45 110 95 115]])})

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

;; Routing

(def tsp-cities
  "Traveling salesperson guide: distances between 13 cities."
  [[0 2451 713 1018 1631 1374 2408 213 2571 875 1420 2145 1972]
   [2451 0 1745 1524 831 1240 959 2596 403 1589 1374 357 579]
   [713 1745 0 355 920 803 1737 851 1858 262 940 1453 1260]
   [1018 1524 355 0 700 862 1395 1123 1584 466 1056 1280 987]
   [1631 831 920 700 0 663 1021 1769 949 796 879 586 371]
   [1374 1240 803 862 663 0 1681 1551 1765 547 225 887 999]
   [2408 959 1737 1395 1021 1681 0 2493 678 1724 1891 1114 701]
   [213 2596 851 1123 1769 1551 2493 0 2699 1038 1605 2300 2099]
   [2571 403 1858 1584 949 1765 678 2699 0 1744 1645 653 600]
   [875 1589 262 466 796 547 1724 1038 1744 0 679 1272 1162]
   [1420 1374 940 1056 879 225 1891 1605 1645 679 0 1017 1200]
   [2145 357 1453 1280 586 887 1114 2300 653 1272 1017 0 504]
   [1972 579 1260 987 371 999 701 2099 600 1162 1200 504 0]])

(def city-blocks
  "Vehicle routing guides: distances between 17 locations."
  [[0 548 776 696 582 274 502 194 308 194 536 502 388 354 468 776 662]
   [548 0 684 308 194 502 730 354 696 742 1084 594 480 674 1016 868 1210]
   [776 684 0 992 878 502 274 810 468 742 400 1278 1164 1130 788 1552 754]
   [696 308 992 0 114 650 878 502 844 890 1232 514 628 822 1164 560 1358]
   [582 194 878 114 0 536 764 388 730 776 1118 400 514 708 1050 674 1244]
   [274 502 502 650 536 0 228 308 194 240 582 776 662 628 514 1050 708]
   [502 730 274 878 764 228 0 536 194 468 354 1004 890 856 514 1278 480]
   [194 354 810 502 388 308 536 0 342 388 730 468 354 320 662 742 856]
   [308 696 468 844 730 194 194 342 0 274 388 810 696 662 320 1084 514]
   [194 742 742 890 776 240 468 388 274 0 342 536 422 388 274 810 468]
   [536 1084 400 1232 1118 582 354 730 388 342 0 878 764 730 388 1152 354]
   [502 594 1278 514 400 776 1004 468 810 536 878 0 114 308 650 274 844]
   [388 480 1164 628 514 662 890 354 696 422 764 114 0 194 536 388 730]
   [354 674 1130 822 708 628 856 320 662 388 730 308 194 0 342 422 536]
   [468 1016 788 1164 1050 514 514 662 320 274 388 650 536 342 0 764 194]
   [776 868 1552 560 674 1050 1278 742 1084 810 1152 274 388 422 764 0 798]
   [662 1210 754 1358 1244 708 480 856 514 468 354 844 730 536 194 798 0]])

(def travel-times
  "Time windows guide: minutes between 17 locations."
  [[0 6 9 8 7 3 6 2 3 2 6 6 4 4 5 9 7]
   [6 0 8 3 2 6 8 4 8 8 13 7 5 8 12 10 14]
   [9 8 0 11 10 6 3 9 5 8 4 15 14 13 9 18 9]
   [8 3 11 0 1 7 10 6 10 10 14 6 7 9 14 6 16]
   [7 2 10 1 0 6 9 4 8 9 13 4 6 8 12 8 14]
   [3 6 6 7 6 0 2 3 2 2 7 9 7 7 6 12 8]
   [6 8 3 10 9 2 0 6 2 5 4 12 10 10 6 15 5]
   [2 4 9 6 4 3 6 0 4 4 8 5 4 3 7 8 10]
   [3 8 5 10 8 2 2 4 0 3 4 9 8 7 3 13 6]
   [2 8 8 10 9 2 5 4 3 0 4 6 5 4 3 9 5]
   [6 13 4 14 13 7 4 8 4 4 0 10 9 8 4 13 4]
   [6 7 15 6 4 9 12 5 9 6 10 0 1 3 7 3 10]
   [4 5 14 7 6 7 10 4 8 5 9 1 0 2 6 4 8]
   [4 8 13 9 8 7 10 3 7 4 8 3 2 0 4 5 6]
   [5 12 9 14 12 6 6 7 3 3 4 7 6 4 0 9 2]
   [9 10 18 6 8 12 15 8 13 9 13 3 4 5 9 0 9]
   [7 14 9 16 14 8 5 10 6 5 4 10 8 6 2 9 0]])

(def traveling-salesperson
  "Traveling salesperson guide."
  {:matrix tsp-cities
   :vehicles 1
   :depot 0
   :search {:first-solution :path-cheapest-arc}})

(def balanced-fleet
  "Vehicle routing guide: four vehicles, shortening the longest route.
   Guided local search reaches the guide's answer on this solver version."
  {:matrix city-blocks
   :vehicles 4
   :depot 0
   :dimensions [{:name :distance
                 :transit {:matrix city-blocks}
                 :capacity 3000
                 :global-span-coefficient 100}]
   :search {:first-solution :path-cheapest-arc
            :metaheuristic :guided-local-search
            :time-limit-s 5}})

(def capacitated-fleet
  "Capacity constraints guide."
  {:matrix city-blocks
   :vehicles 4
   :depot 0
   :dimensions [{:name :load
                 :transit {:demands [0 1 1 2 4 2 4 8 8 1 2 1 2 4 4 8 8]}
                 :capacity [15 15 15 15]}]
   :search {:first-solution :path-cheapest-arc
            :metaheuristic :guided-local-search
            :time-limit-s 1}})

(def time-windowed-fleet
  "Time windows guide."
  {:matrix travel-times
   :vehicles 4
   :depot 0
   :dimensions [{:name :time
                 :transit {:matrix travel-times}
                 :capacity 30
                 :slack 30
                 :start-at-zero? false
                 :finalize-cumuls? true
                 :windows (zipmap (range)
                                  [[0 5] [7 12] [10 15] [16 18] [10 13] [0 5] [5 10] [0 4] [5 10]
                                   [0 3] [10 16] [10 15] [0 5] [5 10] [7 8] [10 15] [11 15]])}]
   :search {:first-solution :path-cheapest-arc}})

(def pickup-delivery-pairs
  "Pickups and deliveries guide: each pair is [pickup delivery]."
  [[1 6] [2 10] [4 3] [5 9] [7 8] [15 11] [13 12] [16 14]])

(def pickup-delivery-fleet
  "Pickups and deliveries guide."
  {:matrix city-blocks
   :vehicles 4
   :depot 0
   :dimensions [{:name :distance
                 :transit {:matrix city-blocks}
                 :capacity 3000
                 :global-span-coefficient 100}]
   :pickups-deliveries {:pairs pickup-delivery-pairs :dimension :distance}
   :search {:first-solution :parallel-cheapest-insertion}})

(defn- longest-route
  "Return the longest route of a routing `result`."
  [result]
  (reduce max (map :cost (get-in result [:ok :routes]))))

(m/=> longest-route [:=> [:cat [:map [:ok map?]]] :int])

(defn- pair-honoured?
  "True when one route of `routes` visits the pickup before the delivery."
  [routes [pickup delivery]]
  (boolean
   (some (fn [route]
           (let [order (mapv :node (:stops route))
                 pickup-at (.indexOf ^java.util.List order pickup)
                 delivery-at (.indexOf ^java.util.List order delivery)]
             (and (not= -1 pickup-at) (< pickup-at delivery-at))))
         routes)))

(m/=> pair-honoured? [:=> [:cat [:sequential map?] [:tuple :int :int]] :boolean])

(defn- pairs-honoured?
  "True when every pickup and delivery pair holds in a routing `result`."
  [result]
  (every? (partial pair-honoured? (get-in result [:ok :routes])) pickup-delivery-pairs))

(m/=> pairs-honoured? [:=> [:cat [:map [:ok map?]]] :boolean])

(def documented
  "Every example and the answer its guide reports. Pickups and deliveries reports
   whether each pair holds, because its total depends on the search."
  {:cp-sat-primer 35
   :cryptarithm 72
   :n-queens 92
   :nurse-rostering 13
   :linear-program 34
   :integer-program 23
   :integer-program-arrays 260
   :max-flow 60
   :min-cost-flow 150
   :assignment 265
   :knapsack 7534
   :bin-packing 4
   :job-shop 11
   :traveling-salesperson 7293
   :balanced-fleet 1552
   :capacitated-fleet 6208
   :time-windowed-fleet 71
   :pickup-delivery-fleet true})

(def Example
  "An example name."
  (into [:enum] (keys documented)))

(defn- whole
  "Round a solver's floating objective to the nearest integer. Pass errors through."
  [value]
  (if (number? value) (Math/round (double value)) value))

(m/=> whole [:=> [:cat :any] :any])

(defn answer
  "Solve the example named `example` and return the number its guide reports."
  [example]
  (case example
    :cp-sat-primer (whole (ok-at (cp-sat/solve cp-sat-primer) [:objective]))
    :cryptarithm (count (ok-at (cp-sat/solve-all cryptarithm {:workers 1}) [:solutions]))
    :n-queens (count (ok-at (cp-sat/solve-all (n-queens 8) {:workers 1}) [:solutions]))
    :nurse-rostering (whole (ok-at (cp-sat/solve (nurse-rostering)) [:objective]))
    :linear-program (whole (ok-at (linear/solve linear-program) [:objective]))
    :integer-program (whole (ok-at (linear/solve integer-program) [:objective]))
    :integer-program-arrays (whole (ok-at (linear/solve integer-program-arrays) [:objective]))
    :max-flow (ok-at (flow/max-flow max-flow-network) [:value])
    :min-cost-flow (ok-at (flow/min-cost-flow min-cost-network) [:cost])
    :assignment (ok-at (assignment/solve worker-costs) [:total-cost])
    :knapsack (ok-at (knapsack/solve fifty-items) [:value])
    :bin-packing (ok-at (bin-packing/solve eleven-items {:time-limit-s 30}) [:bins-used])
    :job-shop (ok-at (scheduling/solve three-jobs) [:makespan])
    :traveling-salesperson (ok-at (routing/solve traveling-salesperson) [:objective])
    :balanced-fleet (longest-route (routing/solve balanced-fleet))
    :capacitated-fleet (reduce + (map :cost (:routes (:ok (routing/solve capacitated-fleet)))))
    :time-windowed-fleet (ok-at (routing/solve time-windowed-fleet) [:objective])
    :pickup-delivery-fleet (pairs-honoured? (routing/solve pickup-delivery-fleet))))

(m/=> answer [:=> [:cat Example] :any])
