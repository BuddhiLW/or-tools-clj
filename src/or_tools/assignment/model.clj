(ns or-tools.assignment.model
  "Linear-sum assignment domain: a worker -> task -> cost table."
  (:require [hive-dsl.result :as r]
            [malli.core :as m]
            [or-tools.kernel.index :as index]
            [or-tools.kernel.schema :as schema]))

(def Costs
  "The cost of each allowed worker and task pair. An absent pair is forbidden."
  [:map-of {:min 1} schema/Label [:map-of schema/Label :int]])

(def Problem
  "An assignment problem."
  [:map {:closed true} [:costs Costs]])

(def Plan
  "A promoted assignment problem."
  [:map {:closed true}
   [:arcs [:vector [:map {:closed true} [:left :int] [:right :int] [:cost :int]]]]
   [:workers [:vector schema/Label]]
   [:tasks [:vector schema/Label]]])

(def Raw
  "What an engine reports. :mates holds [task-index cost] per worker index."
  [:map
   [:status schema/Status]
   [:total-cost {:optional true} :int]
   [:mates {:optional true} [:vector [:tuple :int :int]]]])

(def Solution
  "The outcome of an assignment problem."
  [:map
   [:status schema/Status]
   [:total-cost {:optional true} :int]
   [:assignments {:optional true}
    [:vector [:map {:closed true}
              [:worker schema/Label]
              [:task schema/Label]
              [:cost :int]]]]])

(defn- task-labels
  "Return every task label in `costs`, in order of appearance."
  [costs]
  (vec (mapcat keys (vals costs))))

(m/=> task-labels [:=> [:cat Costs] [:vector schema/Label]])

(defn build-plan
  "Build the plan of a schema-valid assignment problem."
  [{:keys [costs]}]
  (let [workers (index/labels->index (vec (keys costs)))
        tasks (index/labels->index (task-labels costs))]
    {:arcs (vec (for [[worker row] costs
                      [task cost] row]
                  {:left (workers worker) :right (tasks task) :cost cost}))
     :workers (index/index->labels workers)
     :tasks (index/index->labels tasks)}))

(m/=> build-plan [:=> [:cat Problem] Plan])

(defn- check-balanced
  "Return ok of `problem` when workers and tasks are equal in number."
  [{:keys [costs] :as problem}]
  (let [workers (count costs)
        tasks (count (set (task-labels costs)))]
    (if (= workers tasks)
      (r/ok problem)
      (r/err :assignment/unbalanced {:workers workers :tasks tasks}))))

(m/=> check-balanced [:=> [:cat Problem] (schema/result-of Problem)])

(defn promote
  "Validate `problem` and return a Result of its plan."
  [problem]
  (r/let-ok [valid (schema/conform Problem :assignment/invalid-problem problem)
             balanced (check-balanced valid)]
    (r/ok (build-plan balanced))))

(m/=> promote [:=> [:cat :any] (schema/result-of Plan)])

(defn decode
  "Restore caller labels in the raw outcome of `plan`."
  [{:keys [workers tasks]} {:keys [status total-cost mates]}]
  (cond-> {:status status}
    mates (assoc :total-cost total-cost
                 :assignments (vec (map-indexed
                                    (fn [left [right cost]]
                                      {:worker (workers left)
                                       :task (tasks right)
                                       :cost cost})
                                    mates)))))

(m/=> decode [:=> [:cat Plan Raw] Solution])

(defn status-keyword
  "Translate a native LinearSumAssignment status name to a status keyword."
  [status-name]
  (get {"OPTIMAL" :optimal
        "INFEASIBLE" :infeasible
        "POSSIBLE_OVERFLOW" :possible-overflow}
       status-name
       :unknown))

(m/=> status-keyword [:=> [:cat :string] schema/Status])
