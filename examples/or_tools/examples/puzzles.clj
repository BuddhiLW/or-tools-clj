(ns or-tools.examples.puzzles
  "Classic CP-SAT puzzles: sudoku, map colouring, a magic square, a tour and permutations.

   (answer :magic-square-count) => 8"
  (:require [malli.core :as m]
            [or-tools.cp-sat :as cp-sat]))

(def sudoku-givens
  "The sudoku of the Wikipedia Sudoku article, 0 for an empty cell."
  [[5 3 0 0 7 0 0 0 0]
   [6 0 0 1 9 5 0 0 0]
   [0 9 8 0 0 0 0 6 0]
   [8 0 0 0 6 0 0 0 3]
   [4 0 0 8 0 3 0 0 1]
   [7 0 0 0 2 0 0 0 6]
   [0 6 0 0 0 0 2 8 0]
   [0 0 0 4 1 9 0 0 5]
   [0 0 0 0 8 0 0 7 9]])

(defn- cell
  "Name the variable of the sudoku cell at `row` and `col`."
  [row col]
  (keyword "cell" (str "r" row "c" col)))

(m/=> cell [:=> [:cat :int :int] :keyword])

(defn- sudoku-units
  "List the 27 rows, columns and boxes of a 9x9 grid as vectors of cell names."
  []
  (let [rows (for [r (range 9)] (mapv #(cell r %) (range 9)))
        cols (for [c (range 9)] (mapv #(cell % c) (range 9)))
        boxes (for [br (range 3) bc (range 3)]
                (vec (for [r (range 3) c (range 3)] (cell (+ (* 3 br) r) (+ (* 3 bc) c)))))]
    (concat rows cols boxes)))

(m/=> sudoku-units [:=> [:cat] [:sequential [:vector :keyword]]])

(defn- sudoku-model
  "Build the CP-SAT model of a sudoku with `givens`: :all-different on every unit."
  [givens]
  {:variables (into {} (for [r (range 9) c (range 9)] [(cell r c) [1 9]]))
   :constraints (into (mapv (fn [unit] [:all-different unit]) (sudoku-units))
                      (for [r (range 9) c (range 9)
                            :let [given (get-in givens [r c])]
                            :when (pos? given)]
                        [:= (cell r c) given]))})

(m/=> sudoku-model [:=> [:cat [:vector [:vector :int]]] map?])

(defn- sudoku-grid
  "Read the solved grid out of `values`."
  [values]
  (mapv (fn [r] (mapv (fn [c] (values (cell r c))) (range 9))) (range 9)))

(m/=> sudoku-grid [:=> [:cat [:map-of :keyword :int]] [:vector [:vector :int]]])

(def australia-borders
  "The land borders between the mainland states and territories of Australia.
   Tasmania borders none."
  [[:wa :nt] [:wa :sa] [:nt :sa] [:nt :q] [:sa :q] [:sa :nsw] [:sa :v] [:q :nsw] [:nsw :v]])

(def australia-regions
  "The seven regions of the map colouring."
  [:wa :nt :sa :q :nsw :v :t])

(defn- colouring-model
  "Colour `australia-regions` with `colours` colours, neighbours different."
  [colours]
  {:variables (zipmap australia-regions (repeat [0 (dec colours)]))
   :constraints (mapv (fn [[a b]] [:!= a b]) australia-borders)})

(m/=> colouring-model [:=> [:cat :int] map?])

(defn- chromatic-number
  "Return the fewest colours that colour Australia, trying 1, 2, 3 and so on."
  []
  (first (filter (fn [k] (contains? #{:optimal :feasible}
                                    (get-in (cp-sat/solve (colouring-model k)) [:ok :status])))
                 (range 1 (inc (count australia-regions))))))

(m/=> chromatic-number [:=> [:cat] :int])

(defn- magic-square-model
  "Place 1..9 on a 3x3 grid so every row, column and diagonal sums to 15."
  []
  (let [sq (fn [r c] (keyword "square" (str "r" r "c" c)))
        lines (concat (for [r (range 3)] (mapv #(sq r %) (range 3)))
                      (for [c (range 3)] (mapv #(sq % c) (range 3)))
                      [(mapv #(sq % %) (range 3)) (mapv #(sq % (- 2 %)) (range 3))])]
    {:variables (into {} (for [r (range 3) c (range 3)] [(sq r c) [1 9]]))
     :constraints (into [[:all-different (vec (for [r (range 3) c (range 3)] (sq r c)))]]
                        (map (fn [line] [:= (into [:+] line) 15]))
                        lines)}))

(m/=> magic-square-model [:=> [:cat] map?])

(def tour-distances
  "A symmetric distance matrix between six cities."
  [[0 12 29 22 13 24]
   [12 0 19 3 25 6]
   [29 19 0 21 23 28]
   [22 3 21 0 4 5]
   [13 25 23 4 0 16]
   [24 6 28 5 16 0]])

(defn- arc
  "Name the variable saying the tour goes from city `from` to city `to`."
  [from to]
  (keyword "arc" (str from "-" to)))

(m/=> arc [:=> [:cat :int :int] :keyword])

(defn- tour-model
  "Visit every city of `distances` once in one :circuit, minimizing the length."
  [distances]
  (let [n (count distances)
        pairs (for [i (range n) j (range n) :when (not= i j)] [i j])]
    {:variables (into {} (map (fn [[i j]] [(arc i j) :bool])) pairs)
     :constraints [[:circuit (mapv (fn [[i j]] [i j (arc i j)]) pairs)]]
     :objective [:minimize (into [:+] (map (fn [[i j]] [:* (get-in distances [i j]) (arc i j)]))
                                 pairs)]}))

(m/=> tour-model [:=> [:cat [:vector [:vector :int]]] map?])

(defn- involution-model
  "Permutations p of 0..n-1 equal to their own :inverse, that is p(p(i)) = i."
  [n]
  (let [p (mapv #(keyword "p" (str %)) (range n))
        q (mapv #(keyword "q" (str %)) (range n))]
    {:variables (into {} (map (fn [v] [v [0 (dec n)]])) (concat p q))
     :constraints (into [[:inverse p q]] (map (fn [a b] [:= a b]) p q))}))

(m/=> involution-model [:=> [:cat :int] map?])

(defn- solution-count
  "Count every solution of `model`, or return the error category."
  [model]
  (let [result (cp-sat/solve-all model)]
    (or (:error result) (count (get-in result [:ok :solutions])))))

(m/=> solution-count [:=> [:cat map?] :any])

(def documented
  "Every puzzle example and its expected answer, with its source.

   :sudoku is the solution printed in the Wikipedia Sudoku article
   (https://en.wikipedia.org/wiki/Sudoku); the tests check it against the givens and the
   rules by plain Clojure. :sudoku-solution-count is 1: a proper sudoku has one solution.
   :australia-chromatic-number is 3: WA, NT and SA border each other pairwise, so 2 colours
   cannot work, and the tests check a 3-colouring by plain Clojure.
   :australia-two-colours is :infeasible because of the same triangle.
   :australia-three-colourings is 18, counted by brute force over all 3^7 colourings in the
   tests. :magic-square-count is 8, the textbook count of 3x3 magic squares: the Lo Shu
   square under its 8 symmetries. :tour-length is the shortest tour of `tour-distances`,
   found by brute force over every order in the tests. :involutions-of-5 is 26, OEIS A000085,
   also enumerated by brute force in the tests."
  {:sudoku [[5 3 4 6 7 8 9 1 2]
            [6 7 2 1 9 5 3 4 8]
            [1 9 8 3 4 2 5 6 7]
            [8 5 9 7 6 1 4 2 3]
            [4 2 6 8 5 3 7 9 1]
            [7 1 3 9 2 4 8 5 6]
            [9 6 1 5 3 7 2 8 4]
            [2 8 7 4 1 9 6 3 5]
            [3 4 5 2 8 6 1 7 9]]
   :sudoku-solution-count 1
   :australia-chromatic-number 3
   :australia-two-colours :infeasible
   :australia-three-colourings 18
   :magic-square-count 8
   :tour-length 76
   :involutions-of-5 26})

(def Example
  "A puzzle example name."
  (into [:enum] (keys documented)))

(defn answer
  "Solve the puzzle example named `example`."
  [example]
  (case example
    :sudoku (let [result (cp-sat/solve (sudoku-model sudoku-givens))]
              (or (:error result) (sudoku-grid (get-in result [:ok :values]))))
    :sudoku-solution-count (solution-count (sudoku-model sudoku-givens))
    :australia-chromatic-number (chromatic-number)
    :australia-two-colours (get-in (cp-sat/solve (colouring-model 2)) [:ok :status])
    :australia-three-colourings (solution-count (colouring-model 3))
    :magic-square-count (solution-count (magic-square-model))
    :tour-length (let [result (cp-sat/solve (tour-model tour-distances))]
                   (or (:error result) (Math/round (double (get-in result [:ok :objective])))))
    :involutions-of-5 (solution-count (involution-model 5))))

(m/=> answer [:=> [:cat Example] :any])
