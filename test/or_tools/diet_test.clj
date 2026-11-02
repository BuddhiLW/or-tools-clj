(ns or-tools.diet-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [or-tools.examples.diet :as diet]
            [or-tools.linear :as linear]
            [or-tools.support :as support]))

(support/instrument!)

(deftest every-diet-example-reaches-its-documented-answer
  (doseq [[example expected] diet/documented]
    (testing example
      (is (= expected (diet/answer example))))))

(deftest the-table-has-the-shape-the-guide-reports
  (testing "the guide prints 'Number of variables = 77' and 'Number of constraints = 9'"
    (is (= 77 (count diet/foods)))
    (is (= 9 (count diet/nutrients))))
  (is (every? #(= 12 (count %)) diet/foods))
  (is (= 77 (count (set (map first diet/foods))))))

(defn- food-row
  "Return the row of `diet/foods` named `food-name`."
  [food-name]
  (first (filter #(= food-name (first %)) diet/foods)))

(def guide-annual-dollars
  "The guide's 'Annual Foods' output, in dollars per year."
  {"Wheat Flour (Enriched)" 10.774457511918223
   "Liver (Beef)" 0.6907834111074193
   "Cabbage" 4.093268864842877
   "Spinach" 1.8277960703546996
   "Navy Beans, Dried" 22.275425687243036})

(defn- nutrient-totals
  "Sum each nutrient over `daily`, a map of food name to daily dollars."
  [daily]
  (vec (for [n (range (count diet/nutrients))]
         (reduce + (map (fn [[food-name dollars]] (* dollars (nth (food-row food-name) (+ 3 n))))
                        daily)))))

(deftest the-guide-diet-meets-every-minimum-in-plain-clojure
  (let [daily (update-vals guide-annual-dollars #(/ % 365.0))
        totals (nutrient-totals daily)]
    (doseq [[[nutrient minimum] total] (map vector diet/nutrients totals)]
      (testing nutrient
        (is (>= total (- minimum 1e-6)))))
    (testing "the guide's 'Nutrients per day' figures for the slack nutrients"
      (is (= 147.41 (/ (Math/round (* 100 (nth totals 1))) 100.0)))
      (is (= 60.47 (/ (Math/round (* 100 (nth totals 3))) 100.0))))
    (is (< (Math/abs (- 39.6617 (reduce + (vals guide-annual-dollars)))) 1e-4))))

(deftest the-optimum-matches-the-guide-food-by-food
  (let [{:keys [values]} (:ok (linear/solve diet/stigler-problem))
        names (mapv first diet/foods)
        annual (into {}
                     (keep (fn [[variable dollars]]
                             (when (> dollars 1e-9)
                               [(names (parse-long (subs (name variable) 1))) (* 365 dollars)])))
                     values)]
    (is (= (set (keys guide-annual-dollars)) (set (keys annual))))
    (doseq [[food-name dollars] guide-annual-dollars]
      (testing food-name
        (is (< (Math/abs (- dollars (annual food-name))) 1e-6))))))

(deftest dual-prices-certify-the-optimum
  (testing "the duals are feasible for the dual LP and reach the primal cost"
    (let [{:keys [objective duals]} (:ok (linear/solve diet/stigler-problem))
          minimums (map second diet/nutrients)]
      (is (every? #(>= % -1e-9) duals))
      (doseq [food diet/foods]
        (testing (first food)
          (is (<= (reduce + (map * duals (drop 3 food))) (+ 1 1e-9)))))
      (is (< (Math/abs (- objective (reduce + (map * duals minimums)))) 1e-9)))))

(deftrifecta diet-answer or-tools.examples.diet/answer
  {:golden-path "test/golden/examples/diet-answer.edn"
   :cases {:annual-price :stigler-annual-price
           :foods :stigler-foods}
   :gen (gen/elements (keys diet/documented))
   :pred some?
   :num-tests 4
   :mutations [["always-nil" (fn [_] nil)]]})
