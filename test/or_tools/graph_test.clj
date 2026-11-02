(ns or-tools.graph-test
  "Network flows, assignment and knapsack: the combinatorial facades."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [or-tools.assignment :as assignment]
            [or-tools.assignment.model :as assignment-model]
            [or-tools.flow :as flow]
            [or-tools.flow.model :as flow-model]
            [or-tools.knapsack :as knapsack]
            [or-tools.knapsack.model :as knapsack-model]
            [or-tools.support :as support]))

(support/instrument!)

(def documented-max-flow
  {:source 0
   :sink 4
   :arcs (mapv (fn [[from to capacity]] {:from from :to to :capacity capacity})
               [[0 1 20] [0 2 30] [0 3 10] [1 2 40] [1 4 30]
                [2 3 10] [2 4 20] [3 2 5] [3 4 20]])})

(def documented-min-cost
  {:supplies {0 20 3 -5 4 -15}
   :arcs (mapv (fn [[from to capacity unit-cost]]
                 {:from from :to to :capacity capacity :unit-cost unit-cost})
               [[0 1 15 4] [0 2 8 4] [1 2 20 2] [1 3 4 2] [1 4 10 6]
                [2 3 15 1] [2 4 4 3] [3 4 20 2] [4 2 5 3]])})

(def documented-costs
  [[90 76 75 70] [35 85 55 65] [125 95 90 105] [45 110 95 115]])

(defn matrix->costs
  "Build an assignment cost table from a square matrix."
  [matrix]
  (into {}
        (map-indexed (fn [worker row]
                       [worker (into {} (map-indexed vector) row)]))
        matrix))

(def documented-assignment
  {:costs (matrix->costs documented-costs)})

(def labelled-flow
  {:source :s
   :sink :t
   :arcs [{:from :s :to :a :capacity 3} {:from :a :to :t :capacity 2}]})

(def gen-network
  (gen/fmap (fn [capacities]
              {:source :s
               :sink :t
               :arcs (mapv (fn [[from to] capacity] {:from from :to to :capacity capacity})
                           [[:s :a] [:s :b] [:a :b] [:a :t] [:b :t]]
                           capacities)})
            (gen/vector (gen/choose 0 20) 5)))

(defn plan-of-flows
  "Build the plan used to exercise flow decoding."
  []
  {:arcs [{:from 0 :to 1 :capacity 5}] :labels [:a :b]})

(defn cut-value
  "Return the minimum cut of a generated five-arc network, by enumeration."
  [{:keys [arcs]}]
  (let [capacity (into {} (map (juxt (juxt :from :to) :capacity)) arcs)
        cut (fn [source-side]
              (reduce + (for [[[from to] c] capacity
                              :when (and (source-side from) (not (source-side to)))]
                          c)))]
    (reduce min (map cut [#{:s} #{:s :a} #{:s :b} #{:s :a :b}]))))

(defn solved-flow-value
  "Return the max-flow value the native solver reports."
  [problem]
  (get-in (flow/max-flow problem) [:ok :value]))

(deftrifecta max-flow-equals-min-cut or-tools.graph-test/solved-flow-value
  {:gen gen-network
   :property-type :oracle
   :oracle cut-value
   :num-tests 60})

(deftrifecta build-max-flow-plan or-tools.flow.model/build-max-flow-plan
  {:golden-path "test/golden/flow/build-max-flow-plan.edn"
   :cases {:labelled labelled-flow}
   :gen gen-network
   :pred (support/valid flow-model/MaxFlowPlan)
   :num-tests 20
   :mutations [["swapped" (fn [p] (let [plan (flow-model/build-max-flow-plan p)]
                                    (assoc plan :source (:sink plan) :sink (:source plan))))]]})

(deftrifecta promote-max-flow or-tools.flow.model/promote-max-flow
  {:golden-path "test/golden/flow/promote-max-flow.edn"
   :cases {:ok labelled-flow
           :same-terminal (assoc labelled-flow :sink :s)
           :unknown-terminal (assoc labelled-flow :sink :nowhere)
           :negative-capacity (assoc labelled-flow :arcs [{:from :s :to :t :capacity -1}])}
   :gen gen-network
   :pred :ok
   :num-tests 20
   :mutations [["never-fails" (fn [p] {:ok p})]]})

(deftrifecta build-min-cost-plan or-tools.flow.model/build-min-cost-plan
  {:golden-path "test/golden/flow/build-min-cost-plan.edn"
   :cases {:supplies-only-node {:arcs [{:from :a :to :b :capacity 1 :unit-cost 2}]
                                :supplies {:a 1 :c 0}}}
   :mutations [["no-supplies" (fn [p] (assoc (flow-model/build-min-cost-plan p) :supplies {}))]]})

(deftrifecta promote-min-cost or-tools.flow.model/promote-min-cost
  {:golden-path "test/golden/flow/promote-min-cost.edn"
   :cases {:ok documented-min-cost
           :no-unit-cost {:arcs [{:from 0 :to 1 :capacity 1}]}}
   :mutations [["never-fails" (fn [p] {:ok p})]]})

(deftrifecta label-flows or-tools.flow.model/label-flows
  {:golden-path "test/golden/flow/label-flows.edn"
   :apply? true
   :cases {:one [(plan-of-flows) [4]]}
   :mutations [["keeps-indices" (fn [{:keys [arcs]} flows]
                                  (mapv #(assoc %1 :flow %2) arcs flows))]]})

(deftrifecta decode or-tools.flow.model/decode
  {:golden-path "test/golden/flow/decode.edn"
   :apply? true
   :cases {:with-flows [(plan-of-flows) {:status :optimal :value 4 :flows [4]}]
           :without [(plan-of-flows) {:status :bad-input}]}
   :mutations [["identity" (fn [_ raw] raw)]]})

(deftrifecta max-flow-status or-tools.flow.model/max-flow-status
  {:golden-path "test/golden/flow/max-flow-status.edn"
   :cases {:optimal "OPTIMAL" :overflow "POSSIBLE_OVERFLOW" :bad-input "BAD_INPUT"
           :bad-result "BAD_RESULT" :other "?"}
   :gen gen/string-alphanumeric
   :pred keyword?
   :mutations [["always-optimal" (fn [_] :optimal)]]})

(deftrifecta min-cost-status or-tools.flow.model/min-cost-status
  {:golden-path "test/golden/flow/min-cost-status.edn"
   :cases {:optimal "OPTIMAL" :feasible "FEASIBLE" :infeasible "INFEASIBLE"
           :unbalanced "UNBALANCED" :bad-result "BAD_RESULT"
           :bad-cost "BAD_COST_RANGE" :not-solved "NOT_SOLVED" :other "?"}
   :gen gen/string-alphanumeric
   :pred keyword?
   :mutations [["always-optimal" (fn [_] :optimal)]]})

(deftrifecta max-flow or-tools.flow/max-flow
  {:golden-path "test/golden/flow/max-flow.edn"
   :xf (fn [result] (or (:error result) (select-keys (:ok result) [:status :value])))
   :cases {:documented documented-max-flow
           :labelled labelled-flow
           :invalid {:source 1}}
   :gen gen-network
   :pred (comp #{:optimal} :status :ok)
   :num-tests 40
   :mutations [["zero-flow" (fn [_] {:ok {:status :optimal :value 0 :flows []}})]]})

(deftrifecta min-cost-flow or-tools.flow/min-cost-flow
  {:golden-path "test/golden/flow/min-cost-flow.edn"
   :xf (fn [result] (or (:error result) (select-keys (:ok result) [:status :cost])))
   :cases {:documented documented-min-cost
           :unbalanced {:arcs [{:from 0 :to 1 :capacity 1 :unit-cost 1}] :supplies {0 1}}
           :invalid {:arcs []}}
   :mutations [["zero-cost" (fn [_] {:ok {:status :optimal :cost 0 :flows []}})]]})

(deftest documented-flow-examples
  (testing "the max-flow guide reports 60"
    (is (= 60 (get-in (flow/max-flow documented-max-flow) [:ok :value]))))
  (testing "the min-cost-flow guide reports 150"
    (is (= 150 (get-in (flow/min-cost-flow documented-min-cost) [:ok :cost]))))
  (testing "flows carry caller labels and respect capacity"
    (is (= [{:from :s :to :a :capacity 3 :flow 2}
            {:from :a :to :t :capacity 2 :flow 2}]
           (get-in (flow/max-flow labelled-flow) [:ok :flows])))))

(defn permutations
  "Return every permutation of `items`."
  [items]
  (if (empty? items)
    [[]]
    (for [item items
          others (permutations (remove #{item} items))]
      (cons item others))))

(defn brute-force-assignment
  "Return the cheapest total over every permutation of a generated cost matrix."
  [{:keys [costs]}]
  (let [workers (sort (keys costs))]
    (reduce min (for [tasks (permutations workers)]
                  (reduce + (map #(get-in costs [%1 %2]) workers tasks))))))

(defn solved-assignment-cost
  "Return the total the native solver reports."
  [problem]
  (get-in (assignment/solve problem) [:ok :total-cost]))

(def gen-assignment
  (gen/let [size (gen/choose 1 5)
            cells (gen/vector (gen/vector (gen/choose 0 50) size) size)]
    {:costs (matrix->costs cells)}))

(deftrifecta assignment-matches-brute-force or-tools.graph-test/solved-assignment-cost
  {:gen gen-assignment
   :property-type :oracle
   :oracle brute-force-assignment
   :num-tests 60})

(deftrifecta assignment-build-plan or-tools.assignment.model/build-plan
  {:golden-path "test/golden/assignment/build-plan.edn"
   :cases {:labelled {:costs {:ana {:press 1 :lathe 2} :bo {:lathe 3}}}}
   :gen gen-assignment
   :pred (support/valid assignment-model/Plan)
   :num-tests 20
   :mutations [["no-arcs" (fn [p] (assoc (assignment-model/build-plan p) :arcs []))]]})

(deftrifecta assignment-promote or-tools.assignment.model/promote
  {:golden-path "test/golden/assignment/promote.edn"
   :cases {:ok documented-assignment
           :unbalanced {:costs {:a {:x 1 :y 2}}}
           :empty {:costs {}}}
   :gen gen-assignment
   :pred :ok
   :num-tests 20
   :mutations [["never-fails" (fn [p] {:ok p})]]})

(deftrifecta assignment-decode or-tools.assignment.model/decode
  {:golden-path "test/golden/assignment/decode.edn"
   :apply? true
   :cases {:solved [{:arcs [] :workers [:a :b] :tasks [:x :y]}
                    {:status :optimal :total-cost 3 :mates [[1 1] [0 2]]}]
           :infeasible [{:arcs [] :workers [:a] :tasks [:x]} {:status :infeasible}]}
   :mutations [["keeps-indices" (fn [_ raw] (select-keys raw [:status]))]]})

(deftrifecta assignment-status or-tools.assignment.model/status-keyword
  {:golden-path "test/golden/assignment/status.edn"
   :cases {:optimal "OPTIMAL" :infeasible "INFEASIBLE" :overflow "POSSIBLE_OVERFLOW" :other "?"}
   :gen gen/string-alphanumeric
   :pred keyword?
   :mutations [["always-optimal" (fn [_] :optimal)]]})

(deftrifecta assignment-solve or-tools.assignment/solve
  {:golden-path "test/golden/assignment/solve.edn"
   :cases {:documented documented-assignment
           :forbidden-pair {:costs {:a {:x 1} :b {:x 2}}}
           :invalid {:costs 3}}
   :xf (fn [result] (or (:error result) (:status (:ok result))))
   :gen gen-assignment
   :pred (comp #{:optimal} :status :ok)
   :num-tests 40
   :mutations [["always-infeasible" (fn [_] {:ok {:status :infeasible}})]]})

(deftest documented-assignment-example
  (testing "the assignment guide reports a total cost of 265"
    (is (= 265 (get-in (assignment/solve documented-assignment) [:ok :total-cost])))))

(deftest forbidden-pairs-can-make-assignment-infeasible
  (is (= :infeasible
         (get-in (assignment/solve {:costs {:a {:x 1} :b {:x 2} :c {:y 3 :z 3}}})
                 [:ok :status]))))

(def gen-knapsack
  (gen/let [size (gen/choose 1 8)
            values (gen/vector (gen/choose 0 40) size)
            weights (gen/vector (gen/choose 0 15) size)
            capacity (gen/choose 0 40)]
    {:capacities [capacity]
     :items (mapv (fn [id value weight] {:id id :value value :weights [weight]})
                  (range) values weights)}))

(defn brute-force-knapsack
  "Return the best total value of every subset of a generated one-dimensional problem."
  [{:keys [capacities items]}]
  (reduce max 0
          (for [mask (range (bit-shift-left 1 (count items)))
                :let [chosen (keep-indexed (fn [i item] (when (bit-test mask i) item)) items)]
                :when (<= (reduce + (map (comp first :weights) chosen)) (first capacities))]
            (reduce + (map :value chosen)))))

(defn solved-knapsack-value
  "Return the value the native solver reports."
  [problem]
  (get-in (knapsack/solve problem) [:ok :value]))

(deftrifecta knapsack-matches-brute-force or-tools.graph-test/solved-knapsack-value
  {:gen gen-knapsack
   :property-type :oracle
   :oracle brute-force-knapsack
   :num-tests 60})

(def small-knapsack
  {:capacities [10]
   :items [{:id :a :value 60 :weights [5]}
           {:id :b :value 50 :weights [4]}
           {:id :c :value 40 :weights [6]}]})

(deftrifecta solver-violations or-tools.knapsack.model/solver-violations
  {:golden-path "test/golden/knapsack/solver-violations.edn"
   :cases {:fine small-knapsack
           :dimensions {:solver :dynamic-programming :capacities [1 2]
                        :items [{:id 0 :value 1 :weights [1 1]}]}
           :items {:solver :brute-force :capacities [1]
                   :items (mapv (fn [id] {:id id :value 1 :weights [1]}) (range 31))}}
   :gen gen-knapsack
   :pred empty?
   :num-tests 20
   :mutations [["never" (fn [_] [])]]})

(deftrifecta knapsack-build-plan or-tools.knapsack.model/build-plan
  {:golden-path "test/golden/knapsack/build-plan.edn"
   :cases {:two-dimensions {:capacities [5 6]
                            :items [{:id :a :value 1 :weights [2 3]}
                                    {:id :b :value 2 :weights [4 5]}]}}
   :gen gen-knapsack
   :pred (support/valid knapsack-model/Plan)
   :num-tests 20
   :mutations [["untransposed" (fn [p] (assoc (knapsack-model/build-plan p)
                                              :weights (mapv :weights (:items p))))]]})

(deftrifecta knapsack-promote or-tools.knapsack.model/promote
  {:golden-path "test/golden/knapsack/promote.edn"
   :cases {:ok small-knapsack
           :mismatch {:capacities [1 2] :items [{:id :a :value 1 :weights [1]}]}
           :limit {:solver :64-items :capacities [1 2]
                   :items [{:id :a :value 1 :weights [1 1]}]}
           :malformed {:items []}}
   :gen gen-knapsack
   :pred :ok
   :num-tests 20
   :mutations [["never-fails" (fn [p] {:ok p})]]})

(deftrifecta knapsack-decode or-tools.knapsack.model/decode
  {:golden-path "test/golden/knapsack/decode.edn"
   :apply? true
   :cases {:optimal [{:native-solver "x" :ids [:a :b] :values [] :weights []
                      :capacities [] :time-limit-s nil}
                     {:value 9 :selected [1] :optimal? true}]
           :partial [{:native-solver "x" :ids [:a :b] :values [] :weights []
                      :capacities [] :time-limit-s nil}
                     {:value 3 :selected [0] :optimal? false}]}
   :mutations [["always-optimal" (fn [{:keys [ids]} {:keys [value selected]}]
                                   {:status :optimal :value value :items (mapv ids selected)})]]})

(deftrifecta knapsack-solve or-tools.knapsack/solve
  {:golden-path "test/golden/knapsack/solve.edn"
   :cases {:small small-knapsack
           :multidimensional {:capacities [5 5]
                              :items [{:id :a :value 3 :weights [5 1]}
                                      {:id :b :value 4 :weights [1 5]}
                                      {:id :c :value 9 :weights [5 5]}]}
           :invalid {:items 3}}
   :gen gen-knapsack
   :pred (comp #{:optimal :feasible} :status :ok)
   :num-tests 40
   :mutations [["empty" (fn [_] {:ok {:status :optimal :value 0 :items []}})]]})

(deftest knapsack-solutions-are-sound
  (is (= {:status :optimal :value 110 :items [:a :b]}
         (:ok (knapsack/solve small-knapsack))))
  (testing "each single-dimension backend agrees"
    (doseq [solver [:branch-and-bound :dynamic-programming :brute-force :64-items
                    :divide-and-conquer :cp-sat :scip :cbc]]
      (testing solver
        (is (= 110 (get-in (knapsack/solve (assoc small-knapsack :solver solver))
                           [:ok :value])))))))
