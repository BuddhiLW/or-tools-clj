(ns or-tools.examples.planning
  "Planning problems through the linear, flow and knapsack facades: transportation
   with its dual prices, blending, a production plan on two backends, shortest path
   as a min-cost flow and a multi-dimensional knapsack.

   (answer :production-plan-scip) => 36"
  (:require [malli.core :as m]
            [or-tools.flow :as flow]
            [or-tools.knapsack :as knapsack]
            [or-tools.linear :as linear]))

;; Transportation

(def transport-costs
  "Transportation instance: unit cost from each of three plants to four markets.
   Its optimum is certified in or-tools.planning-test by the returned dual prices,
   checked in plain Clojure, and again by min-cost flow."
  [[8 6 10 9]
   [9 12 13 7]
   [14 9 16 5]])

(def transport-supplies
  "Transportation instance: what each plant ships, in full."
  [20 30 25])

(def transport-demands
  "Transportation instance: what each market receives, in full. Totals match supplies."
  [10 25 15 25])

(defn- ship
  "Name the variable holding the units plant `i` sends to market `j`."
  [i j]
  (keyword "ship" (str "p" i "-m" j)))

(m/=> ship [:=> [:cat :int :int] :keyword])

(def transportation
  "Transportation instance as a linear problem. Constraints 0 to 2 are the plant
   supplies and constraints 3 to 6 the market demands, so :duals lists the plant
   prices then the market prices. Variable :ship/p<i>-m<j> is plant i to market j."
  (let [plants (range (count transport-supplies))
        markets (range (count transport-demands))]
    {:solver :glop
     :variables (into {} (for [i plants j markets] [(ship i j) {:lb 0}]))
     :constraints (vec (concat
                        (for [i plants]
                          [:= (into [:+] (map (partial ship i)) markets) (transport-supplies i)])
                        (for [j markets]
                          [:= (into [:+] (map #(ship % j)) plants) (transport-demands j)])))
     :objective [:minimize (into [:+] (for [i plants j markets]
                                        [:* (get-in transport-costs [i j]) (ship i j)]))]}))

(def transport-network
  "Transportation instance as a min-cost flow, with capacities wide enough not to bind."
  {:arcs (vec (for [i (range (count transport-supplies))
                    j (range (count transport-demands))]
                {:from (keyword (str "plant" i))
                 :to (keyword (str "market" j))
                 :capacity 100
                 :unit-cost (get-in transport-costs [i j])}))
   :supplies (merge (into {} (map-indexed (fn [i s] [(keyword (str "plant" i)) s]))
                          transport-supplies)
                    (into {} (map-indexed (fn [j d] [(keyword (str "market" j)) (- d)]))
                          transport-demands))})

(defn- dual-bound
  "Sum each dual price of a linear `solution` times the right-hand side in `rhs`.
   By strong duality this equals the optimum of a feasible bounded LP."
  [solution rhs]
  (reduce + (map * (:duals solution) rhs)))

(m/=> dual-bound [:=> [:cat [:map [:duals [:vector number?]]] [:sequential number?]] number?])

;; Blending

(def whiskas-ingredients
  "PuLP blending case study (https://coin-or.github.io/pulp/CaseStudies/a_blending_problem.html,
   read 2026-11-02): cost in dollars per gram, then grams of protein, fat, fibre and
   salt per gram."
  {:chicken [0.013 0.100 0.080 0.001 0.002]
   :beef [0.008 0.200 0.100 0.005 0.005]
   :mutton [0.010 0.150 0.110 0.003 0.007]
   :rice [0.002 0.000 0.010 0.100 0.002]
   :wheat [0.005 0.040 0.010 0.150 0.008]
   :gel [0.001 0.000 0.000 0.000 0.000]})

(defn- content
  "Sum column `column` of `whiskas-ingredients` over the grams of each ingredient."
  [column]
  (into [:+] (map (fn [[ingredient row]] [:* (nth row column) ingredient]))
        (sort-by key whiskas-ingredients)))

(m/=> content [:=> [:cat :int] vector?])

(def whiskas
  "PuLP blending case study: the cheapest 100 g can with at least 8 g protein and 6 g
   fat, at most 2 g fibre and 0.4 g salt."
  {:variables (into {} (map (fn [ingredient] [ingredient {:lb 0}])) (keys whiskas-ingredients))
   :constraints [[:= (into [:+] (sort (keys whiskas-ingredients))) 100]
                 [:>= (content 1) 8]
                 [:>= (content 2) 6]
                 [:<= (content 3) 2]
                 [:<= (content 4) 0.4]]
   :objective [:minimize (content 0)]})

;; Production plan

(def production-plan-lp
  "Wyndor Glass Co. product mix (Hillier and Lieberman, Introduction to Operations
   Research, chapter 3): weekly batches of doors and windows, profit 3 and 5 per batch,
   under three plant-hour limits, on GLOP with continuous batches."
  {:solver :glop
   :variables {:doors {:lb 0} :windows {:lb 0}}
   :constraints [[:<= :doors 4]
                 [:<= [:* 2 :windows] 12]
                 [:<= [:+ [:* 3 :doors] [:* 2 :windows]] 18]]
   :objective [:maximize [:+ [:* 3 :doors] [:* 5 :windows]]]})

(def production-plan-mip
  "The same product mix on SCIP, with whole batches."
  (-> production-plan-lp
      (assoc :solver :scip)
      (assoc :variables {:doors {:lb 0 :integer? true} :windows {:lb 0 :integer? true}})))

;; Shortest path

(def road-arcs
  "Shortest-path instance: a small weighted digraph as [from to weight]. Its distances
   are checked in or-tools.planning-test by a plain Dijkstra and by enumerating every
   simple path."
  [[:s :t 10] [:s :y 5] [:t :x 1] [:t :y 2] [:y :t 3]
   [:y :x 9] [:y :z 2] [:x :z 4] [:z :x 6] [:z :s 7]])

(def shortest-path-s-x
  "Send one unit from :s to :x over `road-arcs` at least cost: a shortest path."
  {:arcs (mapv (fn [[from to weight]] {:from from :to to :capacity 1 :unit-cost weight})
               road-arcs)
   :supplies {:s 1 :x -1}})

(defn- path-of
  "Follow the arcs carrying flow in a min-cost-flow `solution` from `source`."
  [solution source]
  (let [next-hop (into {} (comp (filter (comp pos? :flow)) (map (juxt :from :to)))
                       (:flows solution))]
    (vec (take-while some? (iterate next-hop source)))))

(m/=> path-of [:=> [:cat map? :keyword] [:vector :keyword]])

;; Multi-dimensional knapsack

(def cargo
  "Multi-dimensional knapsack instance: crates with a value, a weight in kg and a
   volume in litres, under 45 kg and 70 litres. Its optimum is proved by enumerating
   all 256 subsets in or-tools.planning-test."
  {:items [{:id :tents :value 42 :weights [12 30]}
           {:id :rations :value 30 :weights [8 12]}
           {:id :water :value 35 :weights [20 20]}
           {:id :medkits :value 50 :weights [6 18]}
           {:id :radios :value 27 :weights [4 10]}
           {:id :tools :value 22 :weights [15 8]}
           {:id :blankets :value 18 :weights [5 25]}
           {:id :fuel :value 40 :weights [18 16]}]
   :capacities [45 70]})

;; Answers

(defn- solved
  "Return the :ok payload of `result`, or throw its error."
  [result]
  (or (:ok result) (throw (ex-info "solve failed" {:error (:error result)}))))

(m/=> solved [:=> [:cat map?] map?])

(defn- rounded
  "Round `x` to the nearest integer."
  [x]
  (Math/round (double x)))

(m/=> rounded [:=> [:cat number?] :int])

(def documented
  "Every planning example and its expected value, each with its source.
   :transportation, :transportation-dual-bound, :transportation-flow: 585, certified in
     the test by dual feasibility plus strong duality, in plain Clojure.
   :whiskas-cents: 52, the PuLP case study's 'objective Function value of 52 cents'.
   :production-plan-glop, :production-plan-scip: 36, the Wyndor Glass optimum of
     Hillier and Lieberman, proved in the test by enumerating every integer plan.
   :shortest-path-s-x, :shortest-path-route: 9 by s y t x, checked in the test by a
     plain Dijkstra and by enumerating every simple path.
   :cargo-value: 149 (tents, rations, medkits, radios), proved in the test by
     enumerating every subset."
  {:transportation 585
   :transportation-dual-bound 585
   :transportation-flow 585
   :whiskas-cents 52
   :production-plan-glop 36
   :production-plan-scip 36
   :shortest-path-s-x 9
   :shortest-path-route [:s :y :t :x]
   :cargo-value 149})

(def Example
  "A planning example name."
  (into [:enum] (keys documented)))

(defn answer
  "Solve the planning example named `example` and return its expected value."
  [example]
  (case example
    :transportation (rounded (:objective (solved (linear/solve transportation))))
    :transportation-dual-bound (rounded (dual-bound (solved (linear/solve transportation))
                                                    (concat transport-supplies
                                                            transport-demands)))
    :transportation-flow (:cost (solved (flow/min-cost-flow transport-network)))
    :whiskas-cents (rounded (* 100 (:objective (solved (linear/solve whiskas)))))
    :production-plan-glop (rounded (:objective (solved (linear/solve production-plan-lp))))
    :production-plan-scip (rounded (:objective (solved (linear/solve production-plan-mip))))
    :shortest-path-s-x (:cost (solved (flow/min-cost-flow shortest-path-s-x)))
    :shortest-path-route (path-of (solved (flow/min-cost-flow shortest-path-s-x)) :s)
    :cargo-value (:value (solved (knapsack/solve cargo)))))

(m/=> answer [:=> [:cat Example] :any])
