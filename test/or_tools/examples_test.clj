(ns or-tools.examples-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [or-tools.cp-sat :as cp-sat]
            [or-tools.examples :as examples]
            [or-tools.routing :as routing]
            [or-tools.support :as support]))

(support/instrument!)

(deftest every-example-reaches-its-documented-answer
  (doseq [[example expected] examples/documented]
    (testing example
      (is (= expected (examples/answer example))))))

(deftest route-end-times-match-the-raw-java-api
  (let [routes (get-in (routing/solve examples/time-windowed-fleet) [:ok :routes])]
    (is (= [18 24 20 20]
           (mapv (fn [route] (-> route :stops peek :cumuls :time)) routes)))))

(deftest array-integer-program-agrees-across-solvers
  (let [variables [:v0 :v1 :v2 :v3 :v4]
        as-integers (assoc examples/integer-program-arrays
                           :variables (zipmap variables (repeat [0 1000])))]
    (is (= {:v0 8 :v1 21 :v2 0 :v3 2 :v4 3}
           (get-in (cp-sat/solve (dissoc as-integers :solver) {:workers 1})
                   [:ok :values])))))

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
