(ns or-tools.scheduling-cookbook-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [or-tools.cp-sat :as cp-sat]
            [or-tools.examples.scheduling-cookbook :as cookbook]
            [or-tools.support :as support]))

(support/instrument!)

(deftest every-recipe-reaches-its-documented-answer
  (doseq [[example expected] cookbook/documented]
    (testing example
      (is (= expected (cookbook/answer example))))))

(deftrifecta scheduling-cookbook-answer or-tools.examples.scheduling-cookbook/answer
  {:golden-path "test/golden/examples/scheduling-cookbook-answer.edn"
   :cases {:rooms :meeting-rooms
           :project :project-makespan
           :packing :packing-height
           :tank :tank-drain-times
           :rosters :night-rosters
           :pay :best-roster-pay
           :prices :closest-prices}
   :gen (gen/elements (keys cookbook/documented))
   :pred pos-int?
   :num-tests 10
   :mutations [["always-zero" (fn [_] 0)]]})

(defn- values-of
  "Solve `model` and return its values."
  [model]
  (get-in (cp-sat/solve model) [:ok :values]))

(deftest meeting-rooms-match-the-peak-overlap
  (let [peak (apply max (for [t (range 10)]
                          (count (filter (fn [[s e]] (and (<= s t) (< t e))) cookbook/meetings))))
        values (values-of (#'cookbook/meeting-rooms-model))
        room-of (into {} (for [[k v] values
                               :when (and (= "in" (namespace k)) (= 1 v))
                               :let [[mt r] (map parse-long (.split ^String (name k) "-"))]]
                           [mt r]))]
    (is (= 3 peak))
    (is (= (set (range (count cookbook/meetings))) (set (keys room-of))))
    (is (= peak (count (set (vals room-of)))))
    (is (every? (fn [[a b]]
                  (let [[s1 e1] (cookbook/meetings a) [s2 e2] (cookbook/meetings b)]
                    (or (not= (room-of a) (room-of b)) (<= e1 s2) (<= e2 s1))))
                (for [a (keys room-of) b (keys room-of) :when (< a b)] [a b])))))

(defn- permutations
  "Every ordering of the collection `xs`."
  [xs]
  (if (empty? xs) [[]] (for [x xs p (permutations (remove #{x} xs))] (cons x p))))

(defn- serial-schedule
  "Start each task of `order` at its earliest precedence- and capacity-feasible time."
  [order]
  (let [tasks cookbook/project-tasks]
    (reduce
     (fn [starts t]
       (let [{:keys [duration demand]} (tasks t)
             earliest (apply max 0 (for [[a b] cookbook/project-precedences :when (= b t)]
                                     (+ (starts a) (:duration (tasks a)))))
             usage (fn [time] (reduce + (for [[u s] starts
                                              :let [{d :duration q :demand} (tasks u)]
                                              :when (and (<= s time) (< time (+ s d)))]
                                          q)))
             fits? (fn [s] (every? #(<= (+ (usage %) demand) cookbook/project-capacity)
                                   (range s (+ s duration))))]
         (assoc starts t (first (filter fits? (iterate inc earliest))))))
     {}
     order)))

(deftest project-makespan-by-serial-schedules
  (let [precedence-order? (fn [o] (let [pos (zipmap o (range))]
                                    (every? (fn [[a b]] (< (pos a) (pos b)))
                                            cookbook/project-precedences)))
        makespan (fn [starts] (apply max (map (fn [[t s]] (+ s (:duration (cookbook/project-tasks t))))
                                              starts)))]
    (is (= 11 (apply min (for [o (permutations (range 6)) :when (precedence-order? o)]
                           (makespan (serial-schedule o))))))))

(deftest packing-fits-the-strip-without-overlap
  (let [values (values-of (#'cookbook/packing-model))
        boxes (map-indexed (fn [r [w h]] [(values (keyword "x" (str r))) (values (keyword "y" (str r))) w h])
                           cookbook/rectangles)
        overlap? (fn [[x1 y1 w1 h1] [x2 y2 w2 h2]]
                   (and (< x1 (+ x2 w2)) (< x2 (+ x1 w1)) (< y1 (+ y2 h2)) (< y2 (+ y1 h1))))]
    (is (= 25 (reduce + (map (fn [[w h]] (* w h)) cookbook/rectangles))))
    (is (every? (fn [[x y w h]] (and (<= 0 x) (<= (+ x w) 5) (<= 0 y) (<= (+ y h) 5))) boxes))
    (is (not-any? true? (for [a boxes b boxes :when (not (identical? a b))] (overlap? a b))))))

(defn- level-ok?
  "True when the tank level stays within its bounds with drains at `times`."
  [times]
  (let [events (concat cookbook/tank-fills (map vector times (map - cookbook/tank-drains)))]
    (every? (fn [t] (<= 0 (reduce + (map second (filter #(<= (first %) t) events)))
                        cookbook/tank-capacity))
            (range 11))))

(deftest tank-drains-by-brute-force
  (is (= 9 (apply min (for [a (range 11) b (range 11) c (range 11)
                            :when (level-ok? [a b c])]
                        (+ a b c))))))

(defn- rosters
  "Every week of shifts with no three nights in a row, by brute force."
  []
  (filter (fn [week] (not-any? #(= [2 2 2] %) (partition 3 1 week)))
          (reduce (fn [weeks _] (for [w weeks s [0 1 2]] (conj w s))) [[]] (range 7))))

(deftest rosters-by-brute-force
  (is (= 1892 (count (rosters))))
  (is (= 17 (apply max (for [week (rosters) :when (some zero? week)]
                         (reduce + (map cookbook/shift-pay week)))))))

(deftest closest-prices-by-brute-force
  (is (= 2 (apply min (for [a cookbook/catalogue-a b cookbook/catalogue-b] (abs (- a b)))))))
