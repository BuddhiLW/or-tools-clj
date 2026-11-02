(ns or-tools.flow.model
  "Network-flow domain: arcs between labelled nodes, promoted to dense indices."
  (:require [hive-dsl.result :as r]
            [malli.core :as m]
            [or-tools.kernel.index :as index]
            [or-tools.kernel.schema :as schema]))

(def Capacity
  "An arc capacity."
  schema/NonNegInt)

(def MaxFlowArc
  "An arc of a max-flow network."
  [:map {:closed true}
   [:from schema/Label]
   [:to schema/Label]
   [:capacity Capacity]])

(def MaxFlowProblem
  "A max-flow problem."
  [:map {:closed true}
   [:arcs [:vector {:min 1} MaxFlowArc]]
   [:source schema/Label]
   [:sink schema/Label]])

(def MinCostArc
  "An arc of a min-cost-flow network."
  [:map {:closed true}
   [:from schema/Label]
   [:to schema/Label]
   [:capacity Capacity]
   [:unit-cost :int]])

(def MinCostProblem
  "A min-cost-flow problem. :supplies is positive at sources, negative at sinks."
  [:map {:closed true}
   [:arcs [:vector {:min 1} MinCostArc]]
   [:supplies {:optional true} [:map-of schema/Label :int]]])

(def Index
  "A label -> dense integer map."
  [:map-of schema/Label :int])

(def IndexedArc
  "An arc over dense node indices."
  [:map {:closed true}
   [:from :int]
   [:to :int]
   [:capacity Capacity]
   [:unit-cost {:optional true} :int]])

(def MaxFlowPlan
  "A promoted max-flow problem."
  [:map {:closed true}
   [:arcs [:vector IndexedArc]]
   [:source :int]
   [:sink :int]
   [:labels [:vector schema/Label]]])

(def MinCostPlan
  "A promoted min-cost-flow problem."
  [:map {:closed true}
   [:arcs [:vector IndexedArc]]
   [:supplies [:map-of :int :int]]
   [:labels [:vector schema/Label]]])

(def RawFlows
  "The flow on each arc, in plan order."
  [:vector :int])

(def RawMaxFlow
  "What an engine reports for a max-flow plan."
  [:map [:status schema/Status] [:value {:optional true} :int] [:flows {:optional true} RawFlows]])

(def RawMinCost
  "What an engine reports for a min-cost-flow plan."
  [:map [:status schema/Status] [:cost {:optional true} :int] [:flows {:optional true} RawFlows]])

(def LabelledFlow
  "An arc annotated with its flow."
  [:map [:from schema/Label] [:to schema/Label] [:capacity Capacity] [:flow :int]])

(def MaxFlowSolution
  "The outcome of a max-flow problem."
  [:map [:status schema/Status] [:value {:optional true} :int] [:flows {:optional true} [:vector LabelledFlow]]])

(def MinCostSolution
  "The outcome of a min-cost-flow problem."
  [:map [:status schema/Status] [:cost {:optional true} :int] [:flows {:optional true} [:vector LabelledFlow]]])

(defn- index-arc
  "Replace the labels of `arc` by their indices."
  [node-index arc]
  (-> arc
      (update :from node-index)
      (update :to node-index)))

(m/=> index-arc [:=> [:cat Index map?] IndexedArc])

(defn- arc-labels
  "Return every node label in `arcs`, in order of appearance."
  [arcs]
  (vec (mapcat (juxt :from :to) arcs)))

(m/=> arc-labels [:=> [:cat [:sequential map?]] [:vector schema/Label]])

(defn build-max-flow-plan
  "Build the plan of a schema-valid max-flow problem."
  [{:keys [arcs source sink]}]
  (let [node-index (index/labels->index (arc-labels arcs))]
    {:arcs (mapv (partial index-arc node-index) arcs)
     :source (node-index source)
     :sink (node-index sink)
     :labels (index/index->labels node-index)}))

(m/=> build-max-flow-plan [:=> [:cat MaxFlowProblem] MaxFlowPlan])

(defn- check-terminals
  "Return ok of `problem` when source and sink are distinct arc endpoints."
  [{:keys [arcs source sink] :as problem}]
  (let [nodes (set (arc-labels arcs))]
    (cond
      (= source sink) (r/err :flow/invalid-terminals {:source source :sink sink})
      (not (and (contains? nodes source) (contains? nodes sink)))
      (r/err :flow/unknown-terminal {:source source :sink sink})
      :else (r/ok problem))))

(m/=> check-terminals [:=> [:cat MaxFlowProblem] (schema/result-of MaxFlowProblem)])

(defn promote-max-flow
  "Validate a max-flow problem and return a Result of its plan."
  [problem]
  (r/let-ok [valid (schema/conform MaxFlowProblem :flow/invalid-problem problem)
             checked (check-terminals valid)]
    (r/ok (build-max-flow-plan checked))))

(m/=> promote-max-flow [:=> [:cat :any] (schema/result-of MaxFlowPlan)])

(defn build-min-cost-plan
  "Build the plan of a schema-valid min-cost-flow problem."
  [{:keys [arcs supplies]}]
  (let [node-index (index/labels->index (into (arc-labels arcs) (keys supplies)))]
    {:arcs (mapv (partial index-arc node-index) arcs)
     :supplies (into {} (map (fn [[label supply]] [(node-index label) supply])) supplies)
     :labels (index/index->labels node-index)}))

(m/=> build-min-cost-plan [:=> [:cat MinCostProblem] MinCostPlan])

(defn promote-min-cost
  "Validate a min-cost-flow problem and return a Result of its plan."
  [problem]
  (r/map-ok (schema/conform MinCostProblem :flow/invalid-problem problem)
            build-min-cost-plan))

(m/=> promote-min-cost [:=> [:cat :any] (schema/result-of MinCostPlan)])

(defn label-flows
  "Annotate each plan arc with its flow, restoring caller labels."
  [{:keys [arcs labels]} flows]
  (mapv (fn [arc flow]
          (-> arc
              (update :from labels)
              (update :to labels)
              (assoc :flow flow)))
        arcs
        flows))

(m/=> label-flows [:=> [:cat [:map [:arcs [:vector IndexedArc]] [:labels [:vector schema/Label]]]
                        RawFlows]
                   [:vector LabelledFlow]])

(defn decode
  "Replace raw arc flows in `raw` with labelled flows when any exist."
  [plan raw]
  (if (:flows raw)
    (update raw :flows (partial label-flows plan))
    raw))

(m/=> decode [:=> [:cat map? [:or RawMaxFlow RawMinCost]] map?])

(defn max-flow-status
  "Translate a native MaxFlow status name to a status keyword."
  [status-name]
  (get {"OPTIMAL" :optimal
        "POSSIBLE_OVERFLOW" :possible-overflow
        "BAD_INPUT" :bad-input
        "BAD_RESULT" :bad-result}
       status-name
       :unknown))

(m/=> max-flow-status [:=> [:cat :string] schema/Status])

(defn min-cost-status
  "Translate a native MinCostFlow status name to a status keyword."
  [status-name]
  (get {"OPTIMAL" :optimal
        "FEASIBLE" :feasible
        "INFEASIBLE" :infeasible
        "UNBALANCED" :unbalanced
        "BAD_RESULT" :bad-result
        "BAD_COST_RANGE" :bad-cost-range
        "NOT_SOLVED" :not-solved}
       status-name
       :unknown))

(m/=> min-cost-status [:=> [:cat :string] schema/Status])
