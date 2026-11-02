(ns or-tools.knapsack.model
  "Knapsack domain: labelled items, one weight per capacity dimension."
  (:require [hive-dsl.result :as r]
            [malli.core :as m]
            [or-tools.kernel.schema :as schema]))

(def solver-types
  "Each backend, its native enum name and its limits. An absent limit means none."
  {:branch-and-bound {:native "KNAPSACK_MULTIDIMENSION_BRANCH_AND_BOUND_SOLVER"}
   :cp-sat {:native "KNAPSACK_MULTIDIMENSION_CP_SAT_SOLVER"}
   :scip {:native "KNAPSACK_MULTIDIMENSION_SCIP_MIP_SOLVER"}
   :cbc {:native "KNAPSACK_MULTIDIMENSION_CBC_MIP_SOLVER"}
   :dynamic-programming {:native "KNAPSACK_DYNAMIC_PROGRAMMING_SOLVER" :max-dimensions 1}
   :divide-and-conquer {:native "KNAPSACK_DIVIDE_AND_CONQUER_SOLVER" :max-dimensions 1}
   :64-items {:native "KNAPSACK_64ITEMS_SOLVER" :max-dimensions 1 :max-items 64}
   :brute-force {:native "KNAPSACK_BRUTE_FORCE_SOLVER" :max-dimensions 1 :max-items 30}})

(def Item
  "One item."
  [:map {:closed true}
   [:id schema/Label]
   [:value schema/NonNegInt]
   [:weights [:vector {:min 1} schema/NonNegInt]]])

(def Problem
  "A knapsack problem."
  [:map {:closed true}
   [:items [:vector {:min 1} Item]]
   [:capacities [:vector {:min 1} schema/NonNegInt]]
   [:solver {:optional true} (into [:enum] (keys solver-types))]
   [:time-limit-s {:optional true} schema/PosReal]])

(def Plan
  "A promoted knapsack problem. :weights holds one row per capacity."
  [:map {:closed true}
   [:native-solver :string]
   [:ids [:vector schema/Label]]
   [:values [:vector schema/NonNegInt]]
   [:weights [:vector [:vector schema/NonNegInt]]]
   [:capacities [:vector schema/NonNegInt]]
   [:time-limit-s [:maybe schema/PosReal]]])

(def Raw
  "What an engine reports. :selected holds item positions."
  [:map {:closed true}
   [:value :int]
   [:selected [:vector :int]]
   [:optimal? :boolean]])

(def Solution
  "The outcome of a knapsack problem."
  [:map {:closed true}
   [:status [:enum :optimal :feasible]]
   [:value :int]
   [:items [:vector schema/Label]]])

(defn solver-violations
  "Return the limits of the chosen solver that `problem` exceeds."
  [{:keys [items capacities solver]}]
  (let [{:keys [max-dimensions max-items]} (solver-types (or solver :branch-and-bound))]
    (cond-> []
      (and max-dimensions (> (count capacities) max-dimensions))
      (conj {:limit :max-dimensions :allowed max-dimensions :actual (count capacities)})
      (and max-items (> (count items) max-items))
      (conj {:limit :max-items :allowed max-items :actual (count items)}))))

(m/=> solver-violations [:=> [:cat Problem] [:vector map?]])

(defn- check-shape
  "Return ok of `problem` when it fits the solver and every item has one weight
   per capacity."
  [{:keys [items capacities] :as problem}]
  (let [violations (solver-violations problem)
        misshapen (filterv #(not= (count capacities) (count (:weights %))) items)]
    (cond
      (seq violations) (r/err :knapsack/solver-limit {:violations violations})
      (seq misshapen) (r/err :knapsack/weight-mismatch {:items (mapv :id misshapen)})
      :else (r/ok problem))))

(m/=> check-shape [:=> [:cat Problem] (schema/result-of Problem)])

(defn build-plan
  "Build the plan of a valid knapsack problem."
  [{:keys [items capacities solver time-limit-s]}]
  {:native-solver (:native (solver-types (or solver :branch-and-bound)))
   :ids (mapv :id items)
   :values (mapv :value items)
   :weights (mapv (fn [dimension] (mapv #(nth (:weights %) dimension) items))
                  (range (count capacities)))
   :capacities capacities
   :time-limit-s time-limit-s})

(m/=> build-plan [:=> [:cat Problem] Plan])

(defn promote
  "Validate `problem` and return a Result of its plan."
  [problem]
  (r/let-ok [valid (schema/conform Problem :knapsack/invalid-problem problem)
             checked (check-shape valid)]
    (r/ok (build-plan checked))))

(m/=> promote [:=> [:cat :any] (schema/result-of Plan)])

(defn decode
  "Restore item labels in the raw outcome of `plan`."
  [{:keys [ids]} {:keys [value selected optimal?]}]
  {:status (if optimal? :optimal :feasible)
   :value value
   :items (mapv ids selected)})

(m/=> decode [:=> [:cat Plan Raw] Solution])
