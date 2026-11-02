(ns or-tools.puzzles-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [or-tools.cp-sat :as cp-sat]
            [or-tools.examples.puzzles :as puzzles]
            [or-tools.support :as support]))

(support/instrument!)

(deftest every-puzzle-reaches-its-documented-answer
  (doseq [[example expected] puzzles/documented]
    (testing example
      (is (= expected (puzzles/answer example))))))

(deftrifecta puzzles-answer or-tools.examples.puzzles/answer
  {:golden-path "test/golden/examples/puzzles-answer.edn"
   :cases {:sudoku-count :sudoku-solution-count
           :chromatic :australia-chromatic-number
           :two-colours :australia-two-colours
           :magic :magic-square-count
           :tour :tour-length
           :involutions :involutions-of-5}
   :gen (gen/elements [:australia-chromatic-number :magic-square-count :tour-length
                       :involutions-of-5 :australia-three-colourings])
   :pred pos-int?
   :num-tests 10
   :mutations [["always-zero" (fn [_] 0)]]})

(defn- valid-sudoku?
  "True when `grid` holds 1..9 once in every row, column and box."
  [grid]
  (let [digits (set (range 1 10))
        rows grid
        cols (apply map vector grid)
        boxes (for [br (range 3) bc (range 3)]
                (for [r (range 3) c (range 3)] (get-in grid [(+ (* 3 br) r) (+ (* 3 bc) c)])))]
    (every? #(= digits (set %)) (concat rows cols boxes))))

(deftest sudoku-solution-obeys-the-rules-and-the-givens
  (let [grid (:sudoku puzzles/documented)]
    (is (valid-sudoku? grid))
    (is (every? true? (for [r (range 9) c (range 9)
                            :let [given (get-in puzzles/sudoku-givens [r c])]
                            :when (pos? given)]
                        (= given (get-in grid [r c])))))))

(defn- proper-colouring?
  "True when `colours`, a map from region to colour, separates every border."
  [colours]
  (every? (fn [[a b]] (not= (colours a) (colours b))) puzzles/australia-borders))

(defn- colourings
  "Every map from the regions to colours below `k`, by brute force."
  [k]
  (reduce (fn [maps region] (for [m maps c (range k)] (assoc m region c)))
          [{}]
          puzzles/australia-regions))

(deftest australia-colourings-by-brute-force
  (is (empty? (filter proper-colouring? (colourings 2))))
  (is (= 18 (count (filter proper-colouring? (colourings 3))))))

(deftest solver-three-colouring-is-proper
  (let [values (get-in (cp-sat/solve (#'puzzles/colouring-model 3)) [:ok :values])]
    (is (proper-colouring? values))
    (is (every? #(< -1 % 3) (vals values)))))

(defn- permutations
  "Every ordering of the collection `xs`."
  [xs]
  (if (empty? xs)
    [[]]
    (for [x xs p (permutations (remove #{x} xs))] (cons x p))))

(deftest magic-squares-by-brute-force
  (let [lines [[0 1 2] [3 4 5] [6 7 8] [0 3 6] [1 4 7] [2 5 8] [0 4 8] [2 4 6]]
        magic? (fn [sq] (every? (fn [line] (= 15 (reduce + (map #(nth sq %) line)))) lines))]
    (is (= 8 (count (filter magic? (map vec (permutations (range 1 10)))))))))

(defn- tour-length
  "Length of the closed tour visiting `order` from city 0."
  [order]
  (let [route (concat [0] order [0])]
    (reduce + (map #(get-in puzzles/tour-distances [%1 %2]) route (rest route)))))

(deftest shortest-tour-by-brute-force
  (is (= (:tour-length puzzles/documented)
         (apply min (map tour-length (permutations (range 1 6)))))))

(deftest solver-tour-is-one-cycle-of-the-reported-length
  (let [{:keys [values objective]} (:ok (cp-sat/solve (#'puzzles/tour-model puzzles/tour-distances)))
        next-city (into {} (for [[k v] values :when (= 1 v)
                                 :let [[from to] (map parse-long (.split ^String (name k) "-"))]]
                             [from to]))
        cycle (take 6 (iterate next-city 0))]
    (is (= 6 (count next-city)))
    (is (= (set (range 6)) (set cycle)))
    (is (= 0 (next-city (last cycle))))
    (is (= (long objective) (tour-length (rest cycle))))))

(deftest involutions-by-brute-force
  (is (= 26 (count (filter (fn [p] (let [p (vec p)] (every? #(= % (p (p %))) (range 5))))
                           (permutations (range 5)))))))
