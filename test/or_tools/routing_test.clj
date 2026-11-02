(ns or-tools.routing-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [or-tools.routing :as routing]
            [or-tools.routing.model :as model]
            [or-tools.support :as support]))

(support/instrument!)

(def documented-tsp
  "The 13-city distance matrix of the TSP guide."
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

(def line-matrix
  [[0 1 2 3] [1 0 1 2] [2 1 0 1] [3 2 1 0]])

(def tour-problem
  {:matrix line-matrix :vehicles 1 :depot 0
   :search {:first-solution :path-cheapest-arc}})

(def full-problem
  {:matrix line-matrix
   :vehicles 2
   :depot 0
   :dimensions [{:name :load :transit {:demands [0 1 1 1]} :capacity [2 3]}
                {:name :time :transit {:matrix line-matrix} :capacity 30 :slack 5
                 :start-at-zero? false :windows {1 [0 9]}
                 :global-span-coefficient 10 :span-coefficient 1}]
   :pickups-deliveries {:pairs [[1 2]] :dimension :time}
   :droppable {3 100}
   :fixed-vehicle-cost 7
   :search {:metaheuristic :guided-local-search :time-limit-s 1 :log? false}})

(def gen-matrix
  (gen/let [size (gen/choose 2 6)
            cells (gen/vector (gen/vector (gen/choose 1 30) size) size)]
    (vec (map-indexed (fn [i row] (assoc row i 0)) cells))))

(def gen-problem
  (gen/fmap (fn [matrix] {:matrix matrix :vehicles 1 :depot 0}) gen-matrix))

(deftrifecta square or-tools.routing.model/square?
  {:golden-path "test/golden/routing/square.edn"
   :cases {:yes [[0 1] [1 0]] :no [[0 1 2] [1 0 2]]}
   :gen gen-matrix
   :pred true?
   :num-tests 20
   :mutations [["always" (fn [_] true)]]})

(deftrifecta demands-to-matrix or-tools.routing.model/demands->matrix
  {:golden-path "test/golden/routing/demands-to-matrix.edn"
   :cases {:three [0 2 5] :empty []}
   :gen (gen/vector (gen/choose 0 9) 0 6)
   :pred vector?
   :num-tests 20
   :mutations [["transposed" (fn [d] (mapv (fn [_] (vec d)) d))]]})

(deftrifecta terminals or-tools.routing.model/terminals
  {:golden-path "test/golden/routing/terminals.edn"
   :cases {:default {:matrix [[0]]}
           :depot {:matrix [[0]] :vehicles 2 :depot 1}
           :explicit {:matrix [[0]] :starts [1 2] :ends [3 4]}
           :starts-only {:matrix [[0]] :starts [1 2]}}
   :gen gen-problem
   :pred (fn [[starts ends]] (= (count starts) (count ends)))
   :num-tests 20
   :mutations [["single-vehicle" (fn [_] [[0] [0]])]]})

(deftrifecta violations or-tools.routing.model/violations
  {:golden-path "test/golden/routing/violations.edn"
   :cases {:sound tour-problem
           :not-square {:matrix [[0 1]]}
           :mismatch {:matrix [[0 1] [1 0]] :starts [0] :ends [0 1]}
           :vehicles {:matrix [[0 1] [1 0]] :vehicles 3 :starts [0] :ends [0]}
           :out-of-range {:matrix [[0]] :depot 4}
           :dimension-size {:matrix [[0 1] [1 0]]
                            :dimensions [{:name :a :transit {:demands [1]} :capacity 1}]}
           :capacity {:matrix [[0]] :vehicles 2
                      :dimensions [{:name :a :transit {:demands [1]} :capacity [1]}]}
           :duplicate {:matrix [[0]]
                       :dimensions [{:name :a :transit {:demands [1]} :capacity 1}
                                    {:name :a :transit {:demands [1]} :capacity 1}]}
           :unknown-dimension {:matrix [[0 1] [1 0]]
                               :pickups-deliveries {:pairs [] :dimension :nope}}
           :droppable-depot {:matrix [[0]] :droppable {0 1}}
           :pair-depot {:matrix [[0 1] [1 0]]
                        :dimensions [{:name :a :transit {:demands [1 1]} :capacity 1}]
                        :pickups-deliveries {:pairs [[0 1]] :dimension :a}}
           :unbounded {:matrix [[0]] :search {:metaheuristic :tabu-search}}}
   :gen gen-problem
   :pred empty?
   :num-tests 20
   :mutations [["never" (fn [_] [])]]})

(deftrifecta enum-name or-tools.routing.model/enum-name
  {:golden-path "test/golden/routing/enum-name.edn"
   :cases {:simple :savings :dashed :path-cheapest-arc :absent nil}
   :gen (gen/elements model/first-solution-strategies)
   :pred string?
   :num-tests 20
   :mutations [["keeps-dashes" (fn [k] (some-> k name))]]})

(deftrifecta build-plan or-tools.routing.model/build-plan
  {:golden-path "test/golden/routing/build-plan.edn"
   :cases {:tour tour-problem :full full-problem}
   :gen gen-problem
   :pred (support/valid model/Plan)
   :num-tests 20
   :mutations [["no-dimensions" (fn [p] (assoc (model/build-plan p) :dimensions []))]]})

(deftrifecta promote or-tools.routing.model/promote
  {:golden-path "test/golden/routing/promote.edn"
   :cases {:ok tour-problem
           :defect {:matrix [[0 1]]}
           :malformed {:matrix 3}
           :bad-strategy {:matrix [[0]] :search {:first-solution :teleport}}}
   :gen gen-problem
   :pred :ok
   :num-tests 20
   :mutations [["never-fails" (fn [p] {:ok p})]]})

(def plan-fixture
  (:ok (model/promote {:matrix line-matrix :droppable {3 1 2 1}})))

(def route-fixture
  {:vehicle 0 :cost 2 :stops [{:node 0 :cumuls {}} {:node 2 :cumuls {}} {:node 0 :cumuls {}}]})

(deftrifecta dropped-nodes or-tools.routing.model/dropped-nodes
  {:golden-path "test/golden/routing/dropped-nodes.edn"
   :apply? true
   :cases {:one [plan-fixture [route-fixture]]
           :none-visited [plan-fixture []]}
   :mutations [["none" (fn [_ _] [])]]})

(deftrifecta decode or-tools.routing.model/decode
  {:golden-path "test/golden/routing/decode.edn"
   :apply? true
   :cases {:routed [plan-fixture {:status :optimal :objective 2 :routes [route-fixture]}]
           :failed [plan-fixture {:status :no-solution}]}
   :mutations [["identity" (fn [_ raw] raw)]]})

(deftrifecta status-keyword or-tools.routing.model/status-keyword
  {:golden-path "test/golden/routing/status-keyword.edn"
   :cases {:not-solved "ROUTING_NOT_SOLVED" :success "ROUTING_SUCCESS"
           :partial "ROUTING_PARTIAL_SUCCESS_LOCAL_OPTIMUM_NOT_REACHED"
           :fail "ROUTING_FAIL" :timeout "ROUTING_FAIL_TIMEOUT" :invalid "ROUTING_INVALID"
           :infeasible "ROUTING_INFEASIBLE" :optimal "ROUTING_OPTIMAL" :other "?"}
   :gen gen/string-alphanumeric
   :pred keyword?
   :mutations [["always-optimal" (fn [_] :optimal)]]})

(defn tour-consistent?
  "True when the single route visits every node once and its cost sums its arcs."
  [{:keys [matrix] :as problem}]
  (let [{:keys [objective routes]} (:ok (routing/solve problem))
        nodes (mapv :node (:stops (first routes)))
        arc-cost (reduce + (map (fn [from to] (get-in matrix [from to]))
                                nodes
                                (rest nodes)))]
    (and (= 0 (first nodes) (peek nodes))
         (= (set (range (count matrix))) (set nodes))
         (= (inc (count matrix)) (count nodes))
         (= objective arc-cost (:cost (first routes))))))

(deftrifecta tours-are-consistent or-tools.routing-test/tour-consistent?
  {:gen gen-problem
   :pred true?
   :num-tests 30})

(deftrifecta solve or-tools.routing/solve
  {:golden-path "test/golden/routing/solve.edn"
   :xf (fn [result] (or (:error result) (select-keys (:ok result) [:status :objective])))
   :cases {:tour tour-problem
           :invalid {:matrix 3}
           :defect {:matrix [[0 1]]}}
   :gen gen-problem
   :pred (comp #{:feasible :optimal} :status :ok)
   :num-tests 30
   :mutations [["no-solution" (fn [_] {:ok {:status :no-solution}})]]})

(def uniform-5
  [[0 2 2 2 2] [2 0 2 2 2] [2 2 0 2 2] [2 2 2 0 2] [2 2 2 2 0]])

(deftest documented-tsp-example
  (testing "the TSP guide reports 7293 with path-cheapest-arc"
    (is (= 7293 (get-in (routing/solve {:matrix documented-tsp :vehicles 1 :depot 0
                                        :search {:first-solution :path-cheapest-arc}})
                        [:ok :objective])))))

(deftest capacity-limits-each-vehicle
  (let [{:keys [routes]} (:ok (routing/solve
                               {:matrix uniform-5
                                :vehicles 2
                                :dimensions [{:name :load
                                              :transit {:demands [0 2 2 2 2]}
                                              :capacity 4}]
                                :search {:first-solution :path-cheapest-arc}}))]
    (is (= [4 4] (mapv #(-> % :stops peek :cumuls :load) routes)))))

(deftest time-windows-are-honoured
  (let [{:keys [routes]} (:ok (routing/solve
                               {:matrix [[0 2 4] [2 0 2] [4 2 0]]
                                :vehicles 1
                                :dimensions [{:name :time
                                              :transit {:matrix [[0 2 4] [2 0 2] [4 2 0]]}
                                              :capacity 30
                                              :slack 30
                                              :start-at-zero? false
                                              :windows {1 [5 6] 2 [10 12]}}]}))
        arrivals (into {} (map (juxt :node (comp :time :cumuls))) (butlast (:stops (first routes))))]
    (is (<= 5 (arrivals 1) 6))
    (is (<= 10 (arrivals 2) 12))))

(deftest pickup-precedes-delivery
  (let [matrix [[0 1 5 5] [1 0 1 5] [5 1 0 1] [5 5 1 0]]
        {:keys [routes]} (:ok (routing/solve
                               {:matrix matrix
                                :vehicles 2
                                :dimensions [{:name :distance
                                              :transit {:matrix matrix}
                                              :capacity 100}]
                                :pickups-deliveries {:pairs [[3 1]] :dimension :distance}}))
        carrying (first (filter #(some (comp #{3} :node) (:stops %)) routes))
        order (mapv :node (:stops carrying))]
    (is (some #{1} order))
    (is (< (.indexOf order 3) (.indexOf order 1)))))

(deftest expensive-nodes-are-dropped
  (is (= [2] (get-in (routing/solve {:matrix [[0 1 100] [1 0 100] [100 100 0]]
                                     :droppable {1 1000 2 1}})
                     [:ok :dropped]))))

(deftest per-vehicle-terminals
  (let [{:keys [routes]} (:ok (routing/solve {:matrix line-matrix :starts [0 3] :ends [0 3]}))]
    (is (= [0 3] (mapv #(-> % :stops first :node) routes)))
    (is (= [0 3] (mapv #(-> % :stops peek :node) routes)))))

(deftest every-documented-option-reaches-the-solver
  (is (contains? #{:feasible :optimal} (get-in (routing/solve full-problem) [:ok :status]))))
