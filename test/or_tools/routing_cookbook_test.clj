(ns or-tools.routing-cookbook-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [or-tools.examples.routing-cookbook :as cookbook]
            [or-tools.routing :as routing]
            [or-tools.support :as support]))

(support/instrument!)

(deftest every-cookbook-example-reaches-its-documented-answer
  (doseq [[example expected] cookbook/documented]
    (testing example
      (is (= expected (cookbook/answer example))))))

(defn- permutations
  "Return every ordering of `xs`."
  [xs]
  (if (empty? xs)
    [[]]
    (for [x xs more (permutations (remove #{x} xs))] (cons x more))))

(defn- path-cost
  "Sum the arcs of `nodes` over `matrix`."
  [matrix nodes]
  (reduce + (map (fn [a b] (get-in matrix [a b])) nodes (rest nodes))))

(defn- cheapest
  "Return the cheapest path from `start` through every node of `stops` to `end`."
  [matrix start end stops]
  (apply min (map #(path-cost matrix (concat [start] % [end])) (permutations stops))))

(defn- splits
  "Return every way to split `customers` between two vehicles, as [first second]."
  [customers]
  (for [mask (range (bit-shift-left 1 (count customers)))]
    (let [chosen (keep-indexed (fn [i c] (when (bit-test mask i) c)) customers)]
      [chosen (remove (set chosen) customers)])))

(deftest separate-ends-optimum-by-enumeration
  (let [matrix cookbook/crossing-matrix
        best (apply min (for [[a b] (splits [4 5 6 7])
                              :when (and (<= (count a) 2) (<= (count b) 2))]
                          (+ (cheapest matrix 0 2 a) (cheapest matrix 1 3 b))))
        routes (mapv #(mapv :node (:stops %))
                     (get-in (routing/solve cookbook/separate-ends) [:ok :routes]))]
    (is (= 26 best))
    (testing "each vehicle leaves its own start and stops at its own end"
      (is (= [[0 2] [1 3]] (mapv (juxt first peek) routes))))
    (is (= #{4 5 6 7} (set (mapcat #(subvec % 1 (dec (count %))) routes))))
    (is (= best (reduce + (map (partial path-cost matrix) routes))))))

(defn- fleet-optimum
  "Return [cost vehicles-used] of the best two-depot plan for `fixed-cost`, by enumeration."
  [fixed-cost]
  (let [matrix cookbook/depot-matrix
        tour (fn [home stops] (if (empty? stops) 0 (cheapest matrix home home stops)))]
    (apply min-key first
           (for [[a b] (splits [2 3 4 5])
                 :let [used (count (filter seq [a b]))]]
             [(+ (tour 0 a) (tour 1 b) (* fixed-cost used)) used]))))

(deftest fixed-vehicle-cost-optima-by-enumeration
  (is (= [34 2] (fleet-optimum 0)))
  (is (= [148 1] (fleet-optimum 100))))

(deftest fixed-vehicle-cost-enters-the-objective
  (testing "the solver objective prices the routes plus the fixed cost of each running vehicle"
    (is (= 34 (get-in (routing/solve cookbook/free-fleet) [:ok :objective])))
    (is (= 148 (get-in (routing/solve cookbook/costly-fleet) [:ok :objective])))))

(deftrifecta routing-cookbook-answer or-tools.examples.routing-cookbook/answer
  {:golden-path "test/golden/examples/routing-cookbook-answer.edn"
   :cases {:separate-ends :separate-ends-cost
           :free-fleet :free-fleet-cost
           :free-fleet-vehicles :free-fleet-vehicles
           :costly-fleet :costly-fleet-cost
           :costly-fleet-vehicles :costly-fleet-vehicles}
   :gen (gen/elements (keys cookbook/documented))
   :pred pos-int?
   :num-tests 10
   :mutations [["always-zero" (fn [_] 0)]]})
