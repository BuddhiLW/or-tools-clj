(ns or-tools.examples.scheduling-cookbook
  "Scheduling recipes for CP-SAT: optional intervals, cumulative resources, 2D packing,
   a reservoir, an automaton and arithmetic over sparse domains.

   (answer :meeting-rooms) => 3"
  (:require [malli.core :as m]
            [or-tools.cp-sat :as cp-sat]))

(def meetings
  "Meetings with fixed times as [start end), end exclusive."
  [[0 3] [1 4] [2 5] [4 6] [5 8] [6 7]])

(def room-count
  "Rooms available to the meetings."
  4)

(defn- named
  "Build a keyword in namespace `prefix` from the parts."
  [prefix & parts]
  (keyword prefix (apply str (interpose "-" parts))))

(m/=> named [:=> [:cat :string [:* :any]] :keyword])

(defn- meeting-rooms-model
  "Place every meeting in one room with an optional interval per meeting and room.
   A room that is not used hosts nothing; minimize the rooms used."
  []
  (let [ms (range (count meetings))
        rooms (range room-count)
        in-room (fn [mt r] (named "in" mt r))
        used (fn [r] (named "used" r))]
    {:variables (merge (into {} (for [mt ms r rooms] [(in-room mt r) :bool]))
                       (into {} (for [r rooms] [(used r) :bool])))
     :intervals (into {} (for [mt ms r rooms
                               :let [[start end] (meetings mt)]]
                           [(named "slot" mt r) {:start start :size (- end start)
                                                 :present (in-room mt r)}]))
     :constraints (-> []
                      (into (for [mt ms] [:exactly-one (mapv #(in-room mt %) rooms)]))
                      (into (for [r rooms] [:no-overlap (mapv #(named "slot" % r) ms)]))
                      (into (for [r rooms]
                              [:only-if [[:not (used r)]]
                               [:= (into [:+] (map #(in-room % r)) ms) 0]])))
     :objective [:minimize (into [:+] (map used) rooms)]}))

(m/=> meeting-rooms-model [:=> [:cat] map?])

(def project-tasks
  "A resource-constrained project: duration and resource demand of each task."
  [{:duration 3 :demand 2} {:duration 2 :demand 3} {:duration 4 :demand 1}
   {:duration 2 :demand 2} {:duration 3 :demand 2} {:duration 1 :demand 3}])

(def project-precedences
  "Pairs [a b]: task b starts after task a ends."
  [[0 3] [1 3] [2 5] [3 4]])

(def project-capacity
  "Units of the shared resource."
  4)

(defn- project-model
  "Minimize the makespan of `project-tasks` under :cumulative and the precedences.
   The makespan is the :max= of the task ends."
  []
  (let [ts (range (count project-tasks))
        horizon (reduce + (map :duration project-tasks))
        start (fn [t] (named "start" t))
        end (fn [t] [:+ (start t) (get-in project-tasks [t :duration])])]
    {:variables (assoc (into {} (for [t ts] [(start t) [0 horizon]])) :makespan [0 horizon])
     :intervals (into {} (for [t ts] [(named "task" t)
                                      {:start (start t)
                                       :size (get-in project-tasks [t :duration])}]))
     :constraints (-> [[:cumulative project-capacity
                        (mapv (fn [t] [(named "task" t) (get-in project-tasks [t :demand])]) ts)]
                       [:max= :makespan (mapv end ts)]]
                      (into (for [[a b] project-precedences] [:<= (end a) (start b)])))
     :objective [:minimize :makespan]}))

(m/=> project-model [:=> [:cat] map?])

(def rectangles
  "Rectangles [width height] to pack, without rotation."
  [[3 2] [2 2] [2 3] [3 3]])

(def strip-width
  "Width of the strip the rectangles are packed into."
  5)

(defn- packing-model
  "Pack `rectangles` into a strip of `strip-width` with :no-overlap-2d, minimizing the height."
  []
  (let [rs (range (count rectangles))
        height-bound (reduce + (map second rectangles))]
    {:variables (assoc (into {} (for [r rs v ["x" "y"]] [(named v r) [0 height-bound]]))
                       :height [0 height-bound])
     :intervals (into {} (for [r rs
                               :let [[w h] (rectangles r)]
                               [axis var size] [["across" "x" w] ["up" "y" h]]]
                           [(named axis r) {:start (named var r) :size size}]))
     :constraints (-> [[:no-overlap-2d (mapv (fn [r] [(named "across" r) (named "up" r)]) rs)]]
                      (into (for [r rs] [:<= [:+ (named "x" r) (first (rectangles r))] strip-width]))
                      (into (for [r rs] [:<= [:+ (named "y" r) (second (rectangles r))] :height])))
     :objective [:minimize :height]}))

(m/=> packing-model [:=> [:cat] map?])

(def tank-fills
  "Fixed fills of the tank: [time volume]."
  [[0 4] [3 4] [6 4]])

(def tank-drains
  "Drains to schedule within [0 10]: volume of each."
  [3 3 6])

(def tank-capacity
  "The tank holds between 0 and this volume at every time."
  6)

(defn- tank-model
  "Schedule `tank-drains` as early as possible, keeping the level within the tank by :reservoir."
  []
  (let [ds (range (count tank-drains))
        drain (fn [d] (named "drain" d))]
    {:variables (into {} (for [d ds] [(drain d) [0 10]]))
     :constraints [[:reservoir 0 tank-capacity
                    (into (vec tank-fills)
                          (map (fn [d] [(drain d) (- (tank-drains d))]) ds))]]
     :objective [:minimize (into [:+] (map drain) ds)]}))

(m/=> tank-model [:=> [:cat] map?])

(def roster-days
  "Days in the roster week."
  7)

(def shift-pay
  "Pay per shift label: 0 off, 1 day, 2 night."
  [0 2 3])

(def night-automaton
  "States count the nights in a row (0, 1 or 2); a third night has no transition."
  {:start 0
   :finals [0 1 2]
   :transitions [[0 0 0] [1 0 0] [2 0 0]
                 [0 0 1] [1 0 1] [2 0 1]
                 [0 1 2] [1 2 2]]})

(defn- roster-model
  "One worker's week of shifts, never more than two nights in a row, by :automaton.
   With `pay?`, at least one day off and maximize the pay."
  [pay?]
  (let [days (mapv #(named "day" %) (range roster-days))
        {:keys [start finals transitions]} night-automaton
        night (fn [d] (named "night" d))
        off (fn [d] (named "off" d))
        base {:variables (into {} (for [d days] [d [0 2]]))
              :constraints [[:automaton days start finals transitions]]}]
    (if-not pay?
      base
      (let [ix (range roster-days)]
        {:variables (merge (:variables base)
                           (into {} (for [i ix v [(night i) (off i)]] [v :bool])))
         :constraints (-> (:constraints base)
                          (into (for [i ix] [:only-if [(off i)] [:= (days i) 0]]))
                          (into (for [i ix] [:only-if [[:not (off i)]] [:!= (days i) 0]]))
                          (into (for [i ix] [:only-if [(night i)] [:= (days i) 2]]))
                          (into (for [i ix] [:only-if [[:not (night i)]] [:!= (days i) 2]]))
                          (conj [:at-least-one (mapv off ix)]))
         :objective [:maximize
                     (into [:+] (for [i ix]
                                  [:+ [:* (shift-pay 1) (days i)]
                                   [:* (- (shift-pay 2) (* 2 (shift-pay 1))) (night i)]]))]}))))

(m/=> roster-model [:=> [:cat :boolean] map?])

(def catalogue-a
  "Prices a first item can take."
  [2 5 11 17])

(def catalogue-b
  "Prices a second item can take."
  [8 13 20])

(defn- closest-prices-model
  "Pick one price from each catalogue, {:domain ...} variables, minimizing the :abs= gap."
  []
  {:variables {:a {:domain (mapv (fn [p] [p p]) catalogue-a)}
               :b {:domain (mapv (fn [p] [p p]) catalogue-b)}
               :gap [0 100]}
   :constraints [[:abs= :gap [:- :a :b]]]
   :objective [:minimize :gap]})

(m/=> closest-prices-model [:=> [:cat] map?])

(defn- objective-of
  "Solve `model` and return its objective as an integer, or the error category."
  [model]
  (let [result (cp-sat/solve model)]
    (or (:error result) (Math/round (double (get-in result [:ok :objective]))))))

(m/=> objective-of [:=> [:cat map?] :any])

(def documented
  "Every scheduling recipe and its expected answer, with its source.

   Each value is checked in the tests without the solver.
   :meeting-rooms is 3, the most meetings in progress at once, found by a sweep over
   `meetings`; the solver's room plan is also checked by plain Clojure.
   :project-makespan is 11, the best serial schedule over every precedence-feasible
   task order: serial schedule generation reaches an optimal schedule from some order
   for a regular objective such as the makespan.
   :packing-height is 5: the rectangles have area 25 on a strip of width 5, so 5 is a
   lower bound, and the tests check a packing of height 5 by plain Clojure.
   :tank-drain-times is 9, the least sum of drain times found by brute force over all
   11^3 drain times. :night-rosters is 1892, the 7-day sequences over off, day and night
   without three nights in a row, counted by brute force over 3^7 rosters.
   :best-roster-pay is 17, the best pay by brute force over the same rosters with at
   least one day off. :closest-prices is 2 (11 and 13), by brute force over the pairs."
  {:meeting-rooms 3
   :project-makespan 11
   :packing-height 5
   :tank-drain-times 9
   :night-rosters 1892
   :best-roster-pay 17
   :closest-prices 2})

(def Example
  "A scheduling recipe name."
  (into [:enum] (keys documented)))

(defn answer
  "Solve the scheduling recipe named `example`."
  [example]
  (case example
    :meeting-rooms (objective-of (meeting-rooms-model))
    :project-makespan (objective-of (project-model))
    :packing-height (objective-of (packing-model))
    :tank-drain-times (objective-of (tank-model))
    :night-rosters (let [result (cp-sat/solve-all (roster-model false))]
                     (or (:error result) (count (get-in result [:ok :solutions]))))
    :best-roster-pay (objective-of (roster-model true))
    :closest-prices (objective-of (closest-prices-model))))

(m/=> answer [:=> [:cat Example] :any])
