(ns or-tools.examples.routing-cookbook
  "Routing recipes the guides leave out: vehicles with their own start and end nodes,
   and a fixed cost per vehicle that decides how many vehicles run. Each instance is
   small enough that or-tools.routing-cookbook-test proves its optimum by enumeration.

   (answer :separate-ends-cost) => 26"
  (:require [malli.core :as m]
            [or-tools.routing :as routing]))

(defn- manhattan
  "Return the matrix of city-block distances between `points`, each [x y]."
  [points]
  (mapv (fn [[x1 y1]]
          (mapv (fn [[x2 y2]] (+ (abs (- x1 x2)) (abs (- y1 y2)))) points))
        points))

(m/=> manhattan [:=> [:cat [:vector [:tuple :int :int]]] [:vector [:vector :int]]])

(defn- path-cost
  "Sum the arcs of `nodes`, a sequence of matrix positions, over `matrix`."
  [matrix nodes]
  (reduce + (map (fn [from to] (get-in matrix [from to])) nodes (rest nodes))))

(m/=> path-cost [:=> [:cat [:vector [:vector :int]] [:sequential :int]] :int])

(def crossing-points
  "Separate start and end instance: vehicle 0 runs from node 0 to node 2, vehicle 1 from
   node 1 to node 3, and nodes 4 to 7 are customers."
  [[0 0] [10 0] [0 8] [10 8] [2 3] [8 2] [3 7] [9 6]])

(def crossing-matrix
  "City-block distances between `crossing-points`."
  (manhattan crossing-points))

(def separate-ends
  "Two vehicles with their own start and end nodes, each carrying at most two of the
   four customers. With the capacity both vehicles must run, so every arc a route
   takes is a real arc of the matrix."
  {:matrix crossing-matrix
   :starts [0 1]
   :ends [2 3]
   :dimensions [{:name :load
                 :transit {:demands [0 0 0 0 1 1 1 1]}
                 :capacity 2}]})

(def depot-points
  "Fixed vehicle cost instance: vehicle 0 is based at node 0, vehicle 1 at node 1, far
   apart; nodes 2 and 3 sit near node 0 and nodes 4 and 5 near node 1."
  [[0 0] [20 0] [1 4] [4 1] [16 3] [19 5]])

(def depot-matrix
  "City-block distances between `depot-points`."
  (manhattan depot-points))

(def fleet-search
  "Search for the fixed-cost fleets: a first solution only, with no time limit."
  {:first-solution :parallel-cheapest-insertion})

(def free-fleet
  "Two vehicles making round trips from their own depots, free to use."
  {:matrix depot-matrix
   :starts [0 1]
   :ends [0 1]
   :search fleet-search})

(def costly-fleet
  "The same two vehicles, each paying 100 when it leaves its depot."
  (assoc free-fleet :fixed-vehicle-cost 100))

(defn- route-nodes
  "Return the node sequence of each route of a routing `result`."
  [result]
  (mapv (fn [route] (mapv :node (:stops route))) (get-in result [:ok :routes])))

(m/=> route-nodes [:=> [:cat map?] [:vector [:vector :int]]])

(defn- running
  "Return the routes of `routes` that visit a customer, so more than their two ends."
  [routes]
  (filterv #(> (count %) 2) routes))

(m/=> running [:=> [:cat [:vector [:vector :int]]] [:vector [:vector :int]]])

(defn- plan-cost
  "Price `routes` in plain Clojure over `matrix`: the arcs of every running route,
   plus `fixed-cost` for each of them."
  [matrix fixed-cost routes]
  (let [used (running routes)]
    (+ (reduce + (map (partial path-cost matrix) used))
       (* fixed-cost (count used)))))

(m/=> plan-cost [:=> [:cat [:vector [:vector :int]] :int [:vector [:vector :int]]] :int])

(def documented
  "Every routing-cookbook example and its expected value. Each optimum is proved in
   or-tools.routing-cookbook-test by enumerating every split of the customers and
   every visiting order, without the solver.
   :separate-ends-cost: 26, vehicle 0 serving nodes 4 and 6, vehicle 1 nodes 5 and 7.
   :free-fleet-cost and :free-fleet-vehicles: 34 with both vehicles running when
     vehicles cost nothing to use.
   :costly-fleet-cost and :costly-fleet-vehicles: 148 with one vehicle running when
     each vehicle costs 100 to use."
  {:separate-ends-cost 26
   :free-fleet-cost 34
   :free-fleet-vehicles 2
   :costly-fleet-cost 148
   :costly-fleet-vehicles 1})

(def Example
  "A routing-cookbook example name."
  (into [:enum] (keys documented)))

(defn answer
  "Solve the routing-cookbook example named `example`. Costs are priced in plain
   Clojure from the returned routes, not read from the solver's objective."
  [example]
  (case example
    :separate-ends-cost (plan-cost crossing-matrix 0 (route-nodes (routing/solve separate-ends)))
    :free-fleet-cost (plan-cost depot-matrix 0 (route-nodes (routing/solve free-fleet)))
    :free-fleet-vehicles (count (running (route-nodes (routing/solve free-fleet))))
    :costly-fleet-cost (plan-cost depot-matrix 100 (route-nodes (routing/solve costly-fleet)))
    :costly-fleet-vehicles (count (running (route-nodes (routing/solve costly-fleet))))))

(m/=> answer [:=> [:cat Example] :any])
