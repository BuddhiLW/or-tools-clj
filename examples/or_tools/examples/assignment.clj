(ns or-tools.examples.assignment
  "The assignment guides, solved through this wrapper.

   (answer :assignment-teams) => 250"
  (:require [malli.core :as m]
            [or-tools.assignment :as assignment]
            [or-tools.cp-sat :as cp-sat]))

(def worker-costs
  "Linear sum assignment guide."
  {:costs (into {}
                (map-indexed (fn [worker row]
                               [worker (into {} (map-indexed vector) row)]))
                [[90 76 75 70] [35 85 55 65] [125 95 90 105] [45 110 95 115]])})

(def worker-costs-without-one-pair
  "Linear sum assignment guide, variant: worker 0 cannot do task 3."
  (update-in worker-costs [:costs 0] dissoc 3))

(defn- assigned
  "Name the variable assigning `worker` to `task`."
  [worker task]
  (keyword "assigned" (str "worker" worker "-task" task)))

(m/=> assigned [:=> [:cat :int :int] :keyword])

(defn- tasks-of
  "List the assignment variables of `worker` over `tasks`."
  [worker tasks]
  (mapv (partial assigned worker) tasks))

(m/=> tasks-of [:=> [:cat :int [:sequential :int]] [:vector :keyword]])

(defn- costed-assignment
  "Assign each task of `costs` to one worker at minimum cost, under `constraints`."
  [costs constraints]
  (let [workers (range (count costs))
        tasks (range (count (first costs)))]
    {:variables (into {} (for [w workers t tasks] [(assigned w t) :bool]))
     :constraints (into (mapv (fn [t] [:exactly-one (mapv #(assigned % t) workers)]) tasks)
                        constraints)
     :objective [:minimize
                 (into [:+] (for [w workers t tasks]
                              [:* (get-in costs [w t]) (assigned w t)]))]}))

(m/=> costed-assignment [:=> [:cat [:vector [:vector :int]] [:sequential :any]] map?])

(def sized-task-costs
  "Assignment with task sizes guide: ten workers and eight tasks."
  [[90 76 75 70 50 74 12 68]
   [35 85 55 65 48 101 70 83]
   [125 95 90 105 59 120 36 73]
   [45 110 95 115 104 83 37 71]
   [60 105 80 75 59 62 93 88]
   [45 65 110 95 47 31 81 34]
   [38 51 107 41 69 99 115 48]
   [47 85 57 71 92 77 109 36]
   [39 63 97 49 118 56 92 61]
   [47 101 71 60 88 109 52 90]])

(defn- assignment-with-task-sizes
  "Assignment with task sizes guide: each worker takes at most 15 units of work."
  []
  (let [sizes [10 7 3 12 15 4 11 5]
        tasks (range (count sizes))]
    (costed-assignment
     sized-task-costs
     (for [w (range (count sized-task-costs))]
       [:<= (into [:+] (map (fn [t] [:* (sizes t) (assigned w t)])) tasks) 15]))))

(m/=> assignment-with-task-sizes [:=> [:cat] map?])

(def team-costs
  "Assignment with teams guide: six workers and four tasks."
  [[90 76 75 70]
   [35 85 55 65]
   [125 95 90 105]
   [45 110 95 115]
   [60 105 80 75]
   [45 65 110 95]])

(defn- assignment-with-teams
  "Assignment with teams guide: at most two tasks for each team of three."
  []
  (let [tasks (range 4)]
    (costed-assignment
     team-costs
     (concat
      (for [w (range (count team-costs))] [:at-most-one (tasks-of w tasks)])
      (for [team [[0 2 4] [1 3 5]]]
        [:<= (into [:+] (mapcat #(tasks-of % tasks)) team) 2])))))

(m/=> assignment-with-teams [:=> [:cat] map?])

(def group-costs
  "Assignment with allowed groups guide: twelve workers and six tasks."
  [[90 76 75 70 50 74]
   [35 85 55 65 48 101]
   [125 95 90 105 59 120]
   [45 110 95 115 104 83]
   [60 105 80 75 59 62]
   [45 65 110 95 47 31]
   [38 51 107 41 69 99]
   [47 85 57 71 92 77]
   [39 63 97 49 118 56]
   [47 101 71 60 88 109]
   [17 39 103 64 61 92]
   [101 45 83 59 92 27]])

(def allowed-pairs
  "Assignment with allowed groups guide: the pairs of workers that may work together."
  [[[2 3] [1 3] [1 2] [0 1] [0 2]]
   [[6 7] [5 7] [5 6] [4 5] [4 7]]
   [[10 11] [9 11] [9 10] [8 10] [8 11]]])

(defn- worked
  "Name the variable saying that `worker` takes a task."
  [worker]
  (keyword "worked" (str "worker" worker)))

(m/=> worked [:=> [:cat :int] :keyword])

(defn- pair-tuple
  "Render `pair` as one flag per worker of the group of four starting at `first-worker`."
  [first-worker pair]
  (mapv (fn [worker] (if (some #{worker} pair) 1 0))
        (range first-worker (+ first-worker 4))))

(m/=> pair-tuple [:=> [:cat :int [:vector :int]] [:vector :int]])

(defn- assignment-with-groups
  "Assignment with allowed groups guide: two workers of each group of four take tasks."
  []
  (let [workers (range (count group-costs))
        tasks (range 6)
        model (costed-assignment
               group-costs
               (concat
                (for [w workers] [:at-most-one (tasks-of w tasks)])
                (for [w workers] [:= (worked w) (into [:+] (tasks-of w tasks))])
                (map-indexed
                 (fn [group pairs]
                   (let [first-worker (* 4 group)]
                     [:allowed (mapv worked (range first-worker (+ first-worker 4)))
                      (mapv (partial pair-tuple first-worker) pairs)]))
                 allowed-pairs)))]
    (update model :variables into (map (fn [w] [(worked w) :bool])) workers)))

(m/=> assignment-with-groups [:=> [:cat] map?])

(def documented
  "Every assignment example and the answer its guide reports."
  {:assignment 265
   :assignment-forbidden-pair 276
   :assignment-task-sizes 326
   :assignment-teams 250
   :assignment-groups 239})

(def Example
  "An assignment example name."
  (into [:enum] (keys documented)))

(defn- objective-of
  "Solve the CP-SAT `model` and return its objective as an integer, or the error category."
  [model]
  (let [result (cp-sat/solve model)]
    (or (:error result) (Math/round (double (get-in result [:ok :objective]))))))

(m/=> objective-of [:=> [:cat map?] :any])

(defn answer
  "Solve the assignment example named `example` and return the cost its guide reports."
  [example]
  (case example
    :assignment (get-in (assignment/solve worker-costs) [:ok :total-cost])
    :assignment-forbidden-pair (get-in (assignment/solve worker-costs-without-one-pair)
                                       [:ok :total-cost])
    :assignment-task-sizes (objective-of (assignment-with-task-sizes))
    :assignment-teams (objective-of (assignment-with-teams))
    :assignment-groups (objective-of (assignment-with-groups))))

(m/=> answer [:=> [:cat Example] :any])
