(ns or-tools.routing.native
  "Boundary: the IRoutingEngine adapter over RoutingModel."
  (:require [hive-dsl.result :as r]
            [malli.core :as m]
            [or-tools.kernel.schema :as schema]
            [or-tools.native.loader :as loader]
            [or-tools.ports :as ports]
            [or-tools.routing.model :as model])
  (:import [com.google.ortools.constraintsolver Assignment FirstSolutionStrategy$Value
            IntVar LocalSearchMetaheuristic$Value RoutingDimension RoutingIndexManager
            RoutingModel RoutingSearchParameters Solver main]
           [com.google.protobuf Duration]))

(set! *warn-on-reflection* true)

(def ManagerInstance (schema/instance-of RoutingIndexManager))
(def RoutingInstance (schema/instance-of RoutingModel))
(def AssignmentInstance (schema/instance-of Assignment))
(def ParametersInstance (schema/instance-of RoutingSearchParameters))

(defn- ->matrix
  "Build a long[][] from rows of integers."
  ^"[[J" [rows]
  (into-array (Class/forName "[J") (map long-array rows)))

(m/=> ->matrix [:=> [:cat model/Matrix] :any])

(defn- ->manager
  "Build the index manager of `plan`."
  ^RoutingIndexManager [{:keys [matrix vehicles starts ends]}]
  (RoutingIndexManager. (int (count matrix))
                        (int vehicles)
                        (int-array starts)
                        (int-array ends)))

(m/=> ->manager [:=> [:cat model/Plan] ManagerInstance])

(defn- ->parameters
  "Build the native search parameters."
  ^RoutingSearchParameters [{:keys [first-solution metaheuristic time-limit-s solution-limit log?]}]
  (let [builder (.toBuilder (main/defaultRoutingSearchParameters))]
    (when first-solution
      (.setFirstSolutionStrategy builder (FirstSolutionStrategy$Value/valueOf ^String first-solution)))
    (when metaheuristic
      (.setLocalSearchMetaheuristic builder (LocalSearchMetaheuristic$Value/valueOf ^String metaheuristic)))
    (when time-limit-s
      (.setTimeLimit builder ^Duration (.build (.setSeconds (Duration/newBuilder) (long time-limit-s)))))
    (when solution-limit
      (.setSolutionLimit builder (long solution-limit)))
    (when log?
      (.setLogSearch builder true))
    (.build builder)))

(m/=> ->parameters [:=> [:cat model/PlanSearch] ParametersInstance])

(defn- node-indices
  "Return the solver indices a window on `node` constrains: the start of each
   vehicle starting there, the end of each vehicle ending there after starting
   elsewhere, and otherwise the node's single index."
  [^RoutingModel routing ^RoutingIndexManager manager {:keys [starts ends vehicles]} node]
  (let [terminals (concat
                   (keep (fn [vehicle]
                           (when (= node (nth starts vehicle)) (.start routing (int vehicle))))
                         (range vehicles))
                   (keep (fn [vehicle]
                           (when (and (= node (nth ends vehicle))
                                      (not= node (nth starts vehicle)))
                             (.end routing (int vehicle))))
                         (range vehicles)))]
    (if (seq terminals)
      (vec terminals)
      [(.nodeToIndex manager (int node))])))

(m/=> node-indices [:=> [:cat RoutingInstance ManagerInstance model/Plan model/Node] [:vector :int]])

(defn- add-dimension!
  "Declare one dimension with its windows, span costs and finalizers."
  [^RoutingModel routing ^RoutingIndexManager manager {:keys [vehicles] :as plan}
   {dimension-name :name :keys [matrix slack capacities start-at-zero? windows
                                global-span-coefficient span-coefficient finalize-cumuls?]}]
  (let [label (name dimension-name)
        callback (.registerTransitMatrix routing (->matrix matrix))]
    (.addDimensionWithVehicleCapacity routing
                                      (int callback)
                                      (long slack)
                                      (long-array capacities)
                                      (boolean start-at-zero?)
                                      label)
    (let [^RoutingDimension dimension (.getMutableDimension routing label)]
      (doseq [[node [earliest latest]] windows
              index (node-indices routing manager plan node)]
        (let [^IntVar cumul (.cumulVar dimension (long index))]
          (.setRange cumul (long earliest) (long latest))))
      (when (pos? global-span-coefficient)
        (.setGlobalSpanCostCoefficient dimension (long global-span-coefficient)))
      (when (pos? span-coefficient)
        (.setSpanCostCoefficientForAllVehicles dimension (long span-coefficient)))
      (when finalize-cumuls?
        (doseq [vehicle (range vehicles)]
          (.addVariableMinimizedByFinalizer routing
                                            (.cumulVar dimension (.start routing (int vehicle))))
          (.addVariableMinimizedByFinalizer routing
                                            (.cumulVar dimension (.end routing (int vehicle))))))
      nil)))

(m/=> add-dimension! [:=> [:cat RoutingInstance ManagerInstance model/Plan model/PlanDimension] :nil])

(defn- add-pickups-deliveries!
  "Tie each pair to one vehicle and order pickup before delivery."
  [^RoutingModel routing ^RoutingIndexManager manager
   {:keys [pickups-deliveries precedence-dimension]}]
  (when (seq pickups-deliveries)
    (let [^Solver solver (.solver routing)
          ^RoutingDimension dimension (.getMutableDimension routing (name precedence-dimension))]
      (doseq [[pickup delivery] pickups-deliveries]
        (let [pickup-index (.nodeToIndex manager (int pickup))
              delivery-index (.nodeToIndex manager (int delivery))]
          (.addPickupAndDelivery routing pickup-index delivery-index)
          (.addConstraint solver (.makeEquality solver
                                                ^IntVar (.vehicleVar routing pickup-index)
                                                ^IntVar (.vehicleVar routing delivery-index)))
          (.addConstraint solver (.makeLessOrEqual solver
                                                   ^IntVar (.cumulVar dimension pickup-index)
                                                   ^IntVar (.cumulVar dimension delivery-index)))))))
  nil)

(m/=> add-pickups-deliveries! [:=> [:cat RoutingInstance ManagerInstance model/Plan] :nil])

(defn- build-routing
  "Build the native routing model of `plan`."
  ^RoutingModel [^RoutingIndexManager manager {:keys [matrix dimensions droppable fixed-vehicle-cost]
                                               :as plan}]
  (let [routing (RoutingModel. manager)
        cost-callback (.registerTransitMatrix routing (->matrix matrix))]
    (.setArcCostEvaluatorOfAllVehicles routing (int cost-callback))
    (when (pos? fixed-vehicle-cost)
      (.setFixedCostOfAllVehicles routing (long fixed-vehicle-cost)))
    (run! (partial add-dimension! routing manager plan) dimensions)
    (add-pickups-deliveries! routing manager plan)
    (doseq [[node penalty] droppable]
      (.addDisjunction routing
                       (long-array [(.nodeToIndex manager (int node))])
                       (long penalty)))
    routing))

(m/=> build-routing [:=> [:cat ManagerInstance model/Plan] RoutingInstance])

(defn- read-cumuls
  "Read the earliest value of each dimension at solver `index`."
  [^RoutingModel routing ^Assignment assignment dimensions index]
  (into {}
        (map (fn [{dimension-name :name}]
               (let [^RoutingDimension dimension (.getMutableDimension routing (name dimension-name))]
                 [dimension-name (.min assignment ^IntVar (.cumulVar dimension (long index)))])))
        dimensions))

(m/=> read-cumuls [:=> [:cat RoutingInstance AssignmentInstance [:sequential map?] :int]
                   [:map-of :keyword :int]])

(defn- read-route
  "Walk the route of `vehicle` through the solution."
  [^RoutingModel routing ^RoutingIndexManager manager ^Assignment assignment dimensions vehicle]
  (let [stop (fn [index]
               {:node (long (.indexToNode manager (long index)))
                :cumuls (read-cumuls routing assignment dimensions index)})]
    (loop [index (.start routing (int vehicle))
           stops []
           cost 0]
      (if (.isEnd routing index)
        {:vehicle vehicle :cost cost :stops (conj stops (stop index))}
        (let [following (.value assignment ^IntVar (.nextVar routing index))]
          (recur (long following)
                 (conj stops (stop index))
                 (long (+ cost (.getArcCostForVehicle routing index following (long vehicle))))))))))

(m/=> read-route [:=> [:cat RoutingInstance ManagerInstance AssignmentInstance [:sequential map?] :int]
                  model/Route])

(defn- run-solver
  "Build and solve `plan`, returning the raw outcome."
  [{:keys [dimensions vehicles search] :as plan}]
  (let [manager (->manager plan)
        routing (build-routing manager plan)
        assignment (.solveWithParameters routing (->parameters search))
        status (model/status-keyword (.name (.status routing)))]
    (cond-> {:status status}
      assignment
      (assoc :objective (.objectiveValue assignment)
             :routes (mapv (partial read-route routing manager assignment dimensions)
                           (range vehicles))))))

(m/=> run-solver [:=> [:cat model/Plan] model/Raw])

(defn solve-plan
  "Solve `plan`, returning a Result of the raw outcome."
  [plan]
  (r/try-effect* :routing/native-failure
    (loader/ensure-loaded!)
    (run-solver plan)))

(m/=> solve-plan [:=> [:cat model/Plan] (schema/result-of model/Raw)])

(defrecord NativeEngine []
  ports/IRoutingEngine
  (solve-routing [_ plan] (solve-plan plan)))

(def engine
  "The default engine, backed by the native routing library."
  (->NativeEngine))
