(ns or-tools.examples-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [or-tools.cp-sat :as cp-sat]
            [or-tools.examples :as examples]
            [or-tools.examples.assignment :as assignment-examples]
            [or-tools.examples.diet :as diet]
            [or-tools.examples.planning :as planning]
            [or-tools.examples.puzzles :as puzzles]
            [or-tools.examples.routing :as routing-examples]
            [or-tools.examples.routing-cookbook :as routing-cookbook]
            [or-tools.examples.scheduling-cookbook :as scheduling-cookbook]
            [or-tools.routing :as routing]
            [or-tools.support :as support]))

(support/instrument!)

(deftest every-example-reaches-its-documented-answer
  (doseq [[example expected] examples/documented]
    (testing example
      (is (= expected (examples/answer example))))))

(deftest example-names-do-not-collide-across-namespaces
  (let [namespaces [assignment-examples/documented
                    routing-examples/documented
                    puzzles/documented
                    scheduling-cookbook/documented
                    diet/documented
                    planning/documented
                    routing-cookbook/documented]
        own-examples 14]
    (is (= (+ own-examples (reduce + (map count namespaces)))
           (count examples/documented)))))

(deftest route-end-times-match-the-raw-java-api
  (let [routes (get-in (routing/solve routing-examples/time-windowed-fleet) [:ok :routes])]
    (is (= [18 24 20 20]
           (mapv (fn [route] (-> route :stops peek :cumuls :time)) routes)))))

(deftest array-integer-program-agrees-across-solvers
  (let [variables [:v0 :v1 :v2 :v3 :v4]
        as-integers (assoc examples/integer-program-arrays
                           :variables (zipmap variables (repeat [0 1000])))]
    (is (= 260.0
           (get-in (cp-sat/solve (dissoc as-integers :solver) {:workers 1})
                   [:ok :objective])))))

(defn- route-distance
  "Sum the arcs of `route`, a sequence of nodes, over the city-blocks matrix."
  [route]
  (reduce + (map (fn [from to] (get-in routing-examples/city-blocks [from to]))
                 route
                 (rest route))))

(deftest droppable-fleet-data-reproduces-the-guide-routes
  (let [guide-routes [[0 9 14 16 0] [0 12 11 4 3 1 0] [0 7 13 0] [0 8 10 2 5 0]]
        penalty (* 2 1000)]
    (is (= [1324 1872 868 1872] (mapv route-distance guide-routes)))
    (is (= 7936 (+ penalty (reduce + (map route-distance guide-routes)))))))

(deftest droppable-fleet-first-solution-drops-the-guide-visits
  (is (= [6 15] (get-in (routing/solve routing-examples/droppable-fleet) [:ok :dropped]))))

(deftest guided-local-search-beats-the-guide-with-a-valid-plan
  (let [fleet (assoc routing-examples/droppable-fleet
                     :search {:first-solution :path-cheapest-arc
                              :metaheuristic :guided-local-search
                              :time-limit-s 2})
        {:keys [objective dropped routes]} (:ok (routing/solve fleet))
        demands (get-in fleet [:dimensions 0 :transit :demands])
        nodes (mapv (fn [route] (mapv :node (:stops route))) routes)]
    (is (< objective 7936))
    (is (= objective (+ (* 1000 (count dropped)) (reduce + (map route-distance nodes)))))
    (is (every? (fn [route] (<= (reduce + (map demands route)) 15)) nodes))
    (is (= (set (range 17)) (into (set dropped) cat nodes)))))

(deftrifecta answer or-tools.examples/answer
  {:golden-path "test/golden/examples/answer.edn"
   :cases {:linear :linear-program
           :flow :max-flow
           :assignment :assignment
           :knapsack :knapsack
           :tsp :traveling-salesperson}
   :gen (gen/elements [:linear-program :max-flow :min-cost-flow :assignment :job-shop])
   :pred pos-int?
   :num-tests 10
   :mutations [["always-zero" (fn [_] 0)]]})

(deftrifecta assignment-answer or-tools.examples.assignment/answer
  {:golden-path "test/golden/examples/assignment-answer.edn"
   :cases {:linear-sum :assignment
           :forbidden-pair :assignment-forbidden-pair
           :task-sizes :assignment-task-sizes
           :teams :assignment-teams
           :groups :assignment-groups}
   :gen (gen/elements (keys assignment-examples/documented))
   :pred pos-int?
   :num-tests 10
   :mutations [["always-zero" (fn [_] 0)]]})

(deftrifecta routing-answer or-tools.examples.routing/answer
  {:golden-path "test/golden/examples/routing-answer.edn"
   :cases {:tsp :traveling-salesperson
           :balanced :balanced-fleet
           :droppable :droppable-fleet
           :windows :time-windowed-fleet
           :pickup-delivery :pickup-delivery-distance}
   :gen (gen/elements [:traveling-salesperson :balanced-fleet :droppable-fleet
                       :time-windowed-fleet :pickup-delivery-distance])
   :pred pos-int?
   :num-tests 10
   :mutations [["always-zero" (fn [_] 0)]]})

(deftest window-on-a-round-trip-depot-limits-departure-only
  (let [matrix [[0 5] [5 0]]
        result (routing/solve {:matrix matrix
                               :vehicles 1
                               :depot 0
                               :dimensions [{:name :time
                                             :transit {:matrix matrix}
                                             :capacity 30
                                             :windows {0 [0 1]}}]})
        stops (get-in result [:ok :routes 0 :stops])]
    (is (= 0 (-> stops first :cumuls :time)))
    (is (= 10 (-> stops peek :cumuls :time)))))

(deftest window-on-a-separate-end-node-limits-arrival
  (let [matrix [[0 5] [5 0]]
        status (fn [window]
                 (get-in (routing/solve {:matrix matrix
                                         :starts [0]
                                         :ends [1]
                                         :dimensions [{:name :time
                                                       :transit {:matrix matrix}
                                                       :capacity 30
                                                       :windows {1 window}}]})
                         [:ok :status]))]
    (is (contains? #{:optimal :feasible} (status [5 9])))
    (is (not (contains? #{:optimal :feasible} (status [0 1]))))))
