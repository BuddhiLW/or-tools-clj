(ns or-tools.planning-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [or-tools.examples.planning :as planning]
            [or-tools.linear :as linear]
            [or-tools.support :as support]))

(support/instrument!)

(deftest every-planning-example-reaches-its-documented-answer
  (doseq [[example expected] planning/documented]
    (testing example
      (is (= expected (planning/answer example))))))

;; Transportation: a dual certificate checked without the solver.

(def ^:private plant-prices
  "Dual prices of the three supply constraints, derived by hand from the basis
   p0-m1, p1-m0, p1-m2, p1-m3, p2-m1, p2-m3 by setting u1 = 0 and solving
   u_i + v_j = c_ij on the basic cells."
  [-5 0 -2])

(def ^:private market-prices
  "Dual prices of the four demand constraints, from the same basis."
  [9 11 13 7])

(def ^:private hand-plan
  "The plan of that basis, as {[plant market] units}."
  {[0 1] 20 [1 0] 10 [1 2] 15 [1 3] 5 [2 1] 5 [2 3] 20})

(deftest a-hand-certificate-proves-585
  (testing "the plan ships every supply and meets every demand"
    (is (= planning/transport-supplies
           (mapv (fn [i] (reduce + (keep (fn [[[p _] q]] (when (= p i) q)) hand-plan))) (range 3))))
    (is (= planning/transport-demands
           (mapv (fn [j] (reduce + (keep (fn [[[_ m] q]] (when (= m j) q)) hand-plan))) (range 4)))))
  (testing "dual feasibility: no cell is cheaper than its plant and market prices"
    (doseq [i (range 3) j (range 4)]
      (is (<= (+ (plant-prices i) (market-prices j)) (get-in planning/transport-costs [i j])))))
  (testing "strong duality: the plan's cost equals the sum of price times supply or demand"
    (is (= 585 (reduce + (map (fn [[[i j] q]] (* q (get-in planning/transport-costs [i j]))) hand-plan))))
    (is (= 585 (+ (reduce + (map * plant-prices planning/transport-supplies))
                  (reduce + (map * market-prices planning/transport-demands)))))))

(deftest solver-duals-and-reduced-costs-are-a-valid-certificate
  (let [{:keys [objective duals reduced-costs values]} (:ok (linear/solve planning/transportation))
        [u v] (split-at 3 duals)
        ship (fn [i j] (keyword "ship" (str "p" i "-m" j)))]
    (testing "objective equals the sum of dual times right-hand side"
      (is (< (Math/abs (- objective
                          (+ (reduce + (map * u planning/transport-supplies))
                             (reduce + (map * v planning/transport-demands)))))
             1e-9)))
    (doseq [i (range 3) j (range 4)
            :let [cost (get-in planning/transport-costs [i j])
                  reduced (- cost (nth u i) (nth v j))]]
      (testing [i j]
        (is (>= reduced -1e-9) "dual feasibility")
        (is (< (Math/abs (- reduced (reduced-costs (ship i j)))) 1e-9)
            "reduced cost is cost minus the two prices")
        (is (< (Math/abs (* reduced (values (ship i j)))) 1e-9)
            "complementary slackness")))))

;; Blending

(deftest whiskas-blend-matches-the-case-study
  (let [{:keys [values]} (:ok (linear/solve planning/whiskas))]
    (testing "the case study's 'optimal solution is 60% Beef and 40% Gel'"
      (is (< (Math/abs (- 60 (:beef values))) 1e-6))
      (is (< (Math/abs (- 40 (:gel values))) 1e-6)))
    (testing "the plan meets the label in plain Clojure"
      (let [total (fn [column] (reduce + (map (fn [[k row]] (* (nth row column) (values k)))
                                              planning/whiskas-ingredients)))]
        (is (>= (total 1) (- 8 1e-6)))
        (is (>= (total 2) (- 6 1e-6)))
        (is (<= (total 3) (+ 2 1e-6)))
        (is (<= (total 4) (+ 0.4 1e-6)))))))

;; Production plan

(deftest wyndor-optimum-by-enumeration
  (let [feasible (for [doors (range 0 7) windows (range 0 10)
                       :when (and (<= doors 4) (<= (* 2 windows) 12)
                                  (<= (+ (* 3 doors) (* 2 windows)) 18))]
                   [(+ (* 3 doors) (* 5 windows)) doors windows])]
    (is (= [36 2 6] (apply max-key first feasible)))
    (is (= {:doors 2.0 :windows 6.0}
           (get-in (linear/solve planning/production-plan-mip) [:ok :values])))))

;; Shortest path

(defn- dijkstra
  "Return the distance from `source` to every node of `arcs`, each [from to weight]."
  [arcs source]
  (loop [dist {source 0} done #{}]
    (let [frontier (remove (comp done key) dist)]
      (if (empty? frontier)
        dist
        (let [[node d] (apply min-key val frontier)]
          (recur (reduce (fn [acc [from to w]]
                           (if (and (= from node) (< (+ d w) (get acc to Long/MAX_VALUE)))
                             (assoc acc to (+ d w))
                             acc))
                         dist arcs)
                 (conj done node)))))))

(defn- simple-paths
  "Return every simple path from `from` to `to` over `arcs`, each as [cost nodes]."
  [arcs from to]
  (letfn [(walk [node seen cost path]
            (if (= node to)
              [[cost path]]
              (mapcat (fn [[a b w]]
                        (when (and (= a node) (not (seen b)))
                          (walk b (conj seen b) (+ cost w) (conj path b))))
                      arcs)))]
    (walk from #{from} 0 [from])))

(deftest shortest-path-agrees-with-dijkstra-and-enumeration
  (is (= 9 ((dijkstra planning/road-arcs :s) :x)))
  (is (= [9 [:s :y :t :x]] (apply min-key first (simple-paths planning/road-arcs :s :x)))))

;; Multi-dimensional knapsack

(deftest cargo-optimum-by-brute-force
  (let [{:keys [items capacities]} planning/cargo
        subsets (for [mask (range (bit-shift-left 1 (count items)))]
                  (keep-indexed (fn [i item] (when (bit-test mask i) item)) items))
        fits? (fn [chosen]
                (every? (fn [d] (<= (reduce + (map #(nth (:weights %) d) chosen)) (capacities d)))
                        (range (count capacities))))
        best (apply max-key first (for [s subsets :when (fits? s)]
                                    [(reduce + (map :value s)) (set (map :id s))]))]
    (is (= [149 #{:tents :rations :medkits :radios}] best))))

(deftrifecta planning-answer or-tools.examples.planning/answer
  {:golden-path "test/golden/examples/planning-answer.edn"
   :cases {:transportation :transportation
           :whiskas :whiskas-cents
           :production-scip :production-plan-scip
           :shortest-path :shortest-path-s-x
           :cargo :cargo-value}
   :gen (gen/elements [:transportation :whiskas-cents :production-plan-glop
                       :production-plan-scip :shortest-path-s-x :cargo-value])
   :pred pos-int?
   :num-tests 10
   :mutations [["always-zero" (fn [_] 0)]]})
