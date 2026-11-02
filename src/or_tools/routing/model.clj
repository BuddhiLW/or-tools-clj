(ns or-tools.routing.model
  "Vehicle-routing domain: nodes are positions in a square arc-cost matrix."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [malli.core :as m]
            [or-tools.kernel.schema :as schema]))

(def Node
  "A position in the arc-cost matrix."
  schema/NonNegInt)

(def Matrix
  "A non-empty matrix of integers. Rows must be as long as the matrix is tall."
  [:vector {:min 1} [:vector :int]])

(def Transit
  "A dimension's per-arc growth: a full matrix, or a per-node demand."
  [:or
   [:map {:closed true} [:matrix Matrix]]
   [:map {:closed true} [:demands [:vector :int]]]])

(def Window
  "An inclusive [earliest latest] range of a dimension's cumulative value."
  [:tuple :int :int])

(def Dimension
  "A quantity accumulated along each route, such as load, time or distance.
   A window on a vehicle's start node limits departure only.
   :finalize-cumuls? asks the search to minimize each route's start and end value."
  [:map {:closed true}
   [:name :keyword]
   [:transit Transit]
   [:capacity [:or schema/NonNegInt [:vector schema/NonNegInt]]]
   [:slack {:optional true} schema/NonNegInt]
   [:start-at-zero? {:optional true} :boolean]
   [:windows {:optional true} [:map-of Node Window]]
   [:global-span-coefficient {:optional true} schema/NonNegInt]
   [:span-coefficient {:optional true} schema/NonNegInt]
   [:finalize-cumuls? {:optional true} :boolean]])

(def first-solution-strategies
  "The supported first-solution strategies."
  [:automatic :path-cheapest-arc :path-most-constrained-arc :savings :parallel-savings
   :sweep :christofides :all-unperformed :best-insertion
   :parallel-cheapest-insertion :sequential-cheapest-insertion
   :local-cheapest-insertion :local-cheapest-cost-insertion
   :global-cheapest-arc :local-cheapest-arc])

(def metaheuristics
  "The supported local-search metaheuristics."
  [:automatic :greedy-descent :guided-local-search :simulated-annealing
   :tabu-search :generic-tabu-search])

(def Search
  "Search parameters."
  [:map {:closed true}
   [:first-solution {:optional true} (into [:enum] first-solution-strategies)]
   [:metaheuristic {:optional true} (into [:enum] metaheuristics)]
   [:time-limit-s {:optional true} schema/PosInt]
   [:solution-limit {:optional true} schema/PosInt]
   [:log? {:optional true} :boolean]])

(def Problem
  "A vehicle-routing problem. Vehicles share :depot, or use :starts and :ends."
  [:map {:closed true}
   [:matrix Matrix]
   [:vehicles {:optional true} schema/PosInt]
   [:depot {:optional true} Node]
   [:starts {:optional true} [:vector Node]]
   [:ends {:optional true} [:vector Node]]
   [:dimensions {:optional true} [:vector Dimension]]
   [:pickups-deliveries {:optional true}
    [:map {:closed true}
     [:pairs [:vector [:tuple Node Node]]]
     [:dimension :keyword]]]
   [:droppable {:optional true} [:map-of Node schema/NonNegInt]]
   [:fixed-vehicle-cost {:optional true} schema/NonNegInt]
   [:search {:optional true} Search]])

(def PlanDimension
  "A promoted dimension whose transit is always a matrix."
  [:map {:closed true}
   [:name :keyword]
   [:matrix Matrix]
   [:slack schema/NonNegInt]
   [:capacities [:vector schema/NonNegInt]]
   [:start-at-zero? :boolean]
   [:windows [:map-of Node Window]]
   [:global-span-coefficient schema/NonNegInt]
   [:span-coefficient schema/NonNegInt]
   [:finalize-cumuls? :boolean]])

(def PlanSearch
  "Promoted search parameters, holding native enum names."
  [:map {:closed true}
   [:first-solution [:maybe :string]]
   [:metaheuristic [:maybe :string]]
   [:time-limit-s [:maybe schema/PosInt]]
   [:solution-limit [:maybe schema/PosInt]]
   [:log? :boolean]])

(def Plan
  "A promoted routing problem."
  [:map {:closed true}
   [:matrix Matrix]
   [:vehicles schema/PosInt]
   [:starts [:vector Node]]
   [:ends [:vector Node]]
   [:dimensions [:vector PlanDimension]]
   [:pickups-deliveries [:vector [:tuple Node Node]]]
   [:precedence-dimension [:maybe :keyword]]
   [:droppable [:map-of Node schema/NonNegInt]]
   [:fixed-vehicle-cost schema/NonNegInt]
   [:search PlanSearch]])

(def Stop
  "One visit on a route, with the value of each dimension on arrival."
  [:map {:closed true}
   [:node Node]
   [:cumuls [:map-of :keyword :int]]])

(def Route
  "The path of one vehicle."
  [:map {:closed true}
   [:vehicle :int]
   [:cost :int]
   [:stops [:vector Stop]]])

(def Raw
  "What an engine reports."
  [:map
   [:status schema/Status]
   [:objective {:optional true} :int]
   [:routes {:optional true} [:vector Route]]])

(def Solution
  "The outcome of a routing problem."
  [:map
   [:status schema/Status]
   [:objective {:optional true} :int]
   [:routes {:optional true} [:vector Route]]
   [:dropped {:optional true} [:vector Node]]])

(defn square?
  "True when `matrix` has as many columns as rows in every row."
  [matrix]
  (every? #(= (count matrix) (count %)) matrix))

(m/=> square? [:=> [:cat Matrix] :boolean])

(defn demands->matrix
  "Build the transit matrix in which leaving node i costs demand i."
  [demands]
  (mapv (fn [demand] (vec (repeat (count demands) demand))) demands))

(m/=> demands->matrix [:=> [:cat [:vector :int]] [:vector [:vector :int]]])

(defn- dimension-matrix
  "Return the transit matrix of `dimension`."
  [{:keys [transit]}]
  (or (:matrix transit) (demands->matrix (:demands transit))))

(m/=> dimension-matrix [:=> [:cat Dimension] [:vector [:vector :int]]])

(defn terminals
  "Return the [starts ends] of `problem`, each with one node per vehicle."
  [{:keys [vehicles depot starts ends]}]
  (let [fleet (or vehicles (some-> (or starts ends) count) 1)
        home (or depot 0)]
    [(or starts (vec (repeat fleet home)))
     (or ends (vec (repeat fleet home)))]))

(m/=> terminals [:=> [:cat Problem] [:tuple [:vector Node] [:vector Node]]])

(defn- out-of-range
  "Return the nodes in `nodes` that are not positions of a matrix of `size`."
  [size nodes]
  (filterv #(>= % size) nodes))

(m/=> out-of-range [:=> [:cat :int [:sequential Node]] [:vector Node]])

(defn- mentioned-nodes
  "Return every node `problem` names outside the matrix itself."
  [{:keys [dimensions pickups-deliveries droppable] :as problem}]
  (let [[starts ends] (terminals problem)]
    (vec (concat starts
                 ends
                 (keys droppable)
                 (mapcat identity (:pairs pickups-deliveries))
                 (mapcat (comp keys :windows) dimensions)))))

(m/=> mentioned-nodes [:=> [:cat Problem] [:vector Node]])

(defn violations
  "Return every structural defect of a schema-valid `problem`."
  [{:keys [matrix vehicles starts ends dimensions pickups-deliveries droppable search]
    :as problem}]
  (let [size (count matrix)
        [start-nodes end-nodes] (terminals problem)
        fleet (count start-nodes)
        depots (into (set start-nodes) end-nodes)
        names (set (map :name dimensions))
        limited? (or (:time-limit-s search) (:solution-limit search))
        wrong-sized (filterv (fn [dimension]
                               (let [transit (dimension-matrix dimension)]
                                 (not (and (= size (count transit)) (square? transit)))))
                             dimensions)
        wrong-capacity (filterv (fn [{:keys [capacity]}]
                                  (and (vector? capacity) (not= fleet (count capacity))))
                                dimensions)
        out (out-of-range size (mentioned-nodes problem))]
    (cond-> []
      (not (square? matrix)) (conj {:defect :matrix-not-square})
      (not= (count start-nodes) (count end-nodes)) (conj {:defect :starts-ends-mismatch})
      (and vehicles (or starts ends) (not= vehicles fleet))
      (conj {:defect :vehicles-mismatch :vehicles vehicles :terminals fleet})
      (seq out) (conj {:defect :node-out-of-range :nodes out})
      (seq wrong-sized) (conj {:defect :dimension-size :dimensions (mapv :name wrong-sized)})
      (seq wrong-capacity) (conj {:defect :capacity-per-vehicle
                                  :dimensions (mapv :name wrong-capacity)})
      (not= (count names) (count dimensions)) (conj {:defect :duplicate-dimension})
      (and pickups-deliveries (not (contains? names (:dimension pickups-deliveries))))
      (conj {:defect :unknown-dimension :dimension (:dimension pickups-deliveries)})
      (some depots (keys droppable)) (conj {:defect :droppable-depot})
      (some depots (mapcat identity (:pairs pickups-deliveries)))
      (conj {:defect :pickup-delivery-depot})
      (and (contains? #{:guided-local-search :simulated-annealing
                        :tabu-search :generic-tabu-search}
                      (:metaheuristic search))
           (not limited?))
      (conj {:defect :unbounded-metaheuristic :metaheuristic (:metaheuristic search)}))))

(m/=> violations [:=> [:cat Problem] [:vector [:map [:defect :keyword]]]])

(defn enum-name
  "Translate a keyword to the native enum spelling, or nil for nil."
  [k]
  (when k
    (str/upper-case (str/replace (name k) "-" "_"))))

(m/=> enum-name [:=> [:cat [:maybe :keyword]] [:maybe :string]])

(defn- promote-dimension
  "Promote one dimension for a fleet of `fleet` vehicles."
  [fleet {dimension-name :name :keys [capacity slack start-at-zero? windows
                                      global-span-coefficient span-coefficient
                                      finalize-cumuls?]
          :as dimension}]
  {:name dimension-name
   :matrix (dimension-matrix dimension)
   :slack (or slack 0)
   :capacities (if (vector? capacity) capacity (vec (repeat fleet capacity)))
   :start-at-zero? (if (some? start-at-zero?) start-at-zero? true)
   :windows (or windows {})
   :global-span-coefficient (or global-span-coefficient 0)
   :span-coefficient (or span-coefficient 0)
   :finalize-cumuls? (boolean finalize-cumuls?)})

(m/=> promote-dimension [:=> [:cat :int Dimension] PlanDimension])

(defn- promote-search
  "Promote search parameters."
  [{:keys [first-solution metaheuristic time-limit-s solution-limit log?]}]
  {:first-solution (enum-name first-solution)
   :metaheuristic (enum-name metaheuristic)
   :time-limit-s time-limit-s
   :solution-limit solution-limit
   :log? (boolean log?)})

(m/=> promote-search [:=> [:cat [:maybe Search]] PlanSearch])

(defn build-plan
  "Build the plan of a structurally sound `problem`."
  [{:keys [matrix dimensions pickups-deliveries droppable fixed-vehicle-cost search]
    :as problem}]
  (let [[starts ends] (terminals problem)
        fleet (count starts)]
    {:matrix matrix
     :vehicles fleet
     :starts starts
     :ends ends
     :dimensions (mapv (partial promote-dimension fleet) dimensions)
     :pickups-deliveries (vec (:pairs pickups-deliveries))
     :precedence-dimension (:dimension pickups-deliveries)
     :droppable (or droppable {})
     :fixed-vehicle-cost (or fixed-vehicle-cost 0)
     :search (promote-search search)}))

(m/=> build-plan [:=> [:cat Problem] Plan])

(defn- check-structure
  "Return ok of `problem` when it has no structural defect."
  [problem]
  (let [defects (violations problem)]
    (if (empty? defects)
      (r/ok problem)
      (r/err :routing/invalid-structure {:defects defects}))))

(m/=> check-structure [:=> [:cat Problem] (schema/result-of Problem)])

(defn promote
  "Validate `problem` and return a Result of its plan."
  [problem]
  (r/let-ok [valid (schema/conform Problem :routing/invalid-problem problem)
             sound (check-structure valid)]
    (r/ok (build-plan sound))))

(m/=> promote [:=> [:cat :any] (schema/result-of Plan)])

(defn dropped-nodes
  "Return the droppable nodes of `plan` that no route in `routes` visits."
  [plan routes]
  (let [visited (into #{} (comp (mapcat :stops) (map :node)) routes)]
    (vec (sort (remove visited (keys (:droppable plan)))))))

(m/=> dropped-nodes [:=> [:cat Plan [:vector Route]] [:vector Node]])

(defn decode
  "Add the dropped nodes to the raw outcome of `plan`."
  [plan raw]
  (if-let [routes (:routes raw)]
    (assoc raw :dropped (dropped-nodes plan routes))
    raw))

(m/=> decode [:=> [:cat Plan Raw] Solution])

(defn status-keyword
  "Translate a native RoutingSearchStatus name to a status keyword."
  [status-name]
  (get {"ROUTING_NOT_SOLVED" :not-solved
        "ROUTING_SUCCESS" :feasible
        "ROUTING_PARTIAL_SUCCESS_LOCAL_OPTIMUM_NOT_REACHED" :feasible
        "ROUTING_FAIL" :no-solution
        "ROUTING_FAIL_TIMEOUT" :timeout
        "ROUTING_INVALID" :invalid
        "ROUTING_INFEASIBLE" :infeasible
        "ROUTING_OPTIMAL" :optimal}
       status-name
       :unknown))

(m/=> status-keyword [:=> [:cat :string] schema/Status])
