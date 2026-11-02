(ns or-tools.scheduling
  "Job-shop scheduling, compiled to a CP-SAT problem.

   (solve {:jobs {:j0 [{:machine :m0 :duration 3} {:machine :m1 :duration 2}]}})"
  (:require [hive-dsl.result :as r]
            [malli.core :as m]
            [or-tools.cp-sat :as cp-sat]
            [or-tools.cp-sat.model :as cp-model]
            [or-tools.kernel.schema :as schema]))

(def Operation
  "One step of a job."
  [:map {:closed true}
   [:machine schema/Label]
   [:duration schema/NonNegInt]])

(def Problem
  "A job-shop problem. :horizon defaults to the sum of all durations."
  [:map {:closed true}
   [:jobs [:map-of {:min 1} schema/Label [:vector {:min 1} Operation]]]
   [:horizon {:optional true} schema/NonNegInt]])

(def Task
  "A flattened operation of a problem."
  [:map {:closed true}
   [:job schema/Label]
   [:position :int]
   [:machine schema/Label]
   [:duration schema/NonNegInt]
   [:start-var :keyword]
   [:interval :keyword]])

(def Slot
  "A scheduled operation."
  [:map {:closed true}
   [:job schema/Label]
   [:position :int]
   [:start :int]
   [:end :int]])

(def Solution
  "The outcome of a scheduling problem."
  [:map
   [:status schema/Status]
   [:makespan {:optional true} :int]
   [:machines {:optional true} [:map-of schema/Label [:vector Slot]]]])

(defn tasks
  "Flatten `problem` into tasks with deterministic variable names."
  [{:keys [jobs]}]
  (vec
   (for [[job-number [job operations]] (map-indexed vector (sort-by (comp str first) jobs))
         [position {:keys [machine duration]}] (map-indexed vector operations)]
     {:job job
      :position position
      :machine machine
      :duration duration
      :start-var (keyword "start" (str "job" job-number "-op" position))
      :interval (keyword "interval" (str "job" job-number "-op" position))})))

(m/=> tasks [:=> [:cat Problem] [:vector Task]])

(defn horizon-of
  "Return the latest time any task may end."
  [{:keys [jobs horizon]}]
  (or horizon (reduce + (map :duration (mapcat second jobs)))))

(m/=> horizon-of [:=> [:cat Problem] :int])

(defn ->cp-sat
  "Compile a job-shop problem to a CP-SAT problem minimizing the makespan."
  [problem]
  (let [all (tasks problem)
        horizon (horizon-of problem)
        by-job (vals (group-by :job all))
        precedences (for [operations by-job
                          [before after] (partition 2 1 operations)]
                      [:<= [:+ (:start-var before) (:duration before)] (:start-var after)])
        no-overlaps (for [[_ on-machine] (sort-by (comp str first) (group-by :machine all))]
                      [:no-overlap (mapv :interval on-machine)])
        endings (for [operations by-job
                      :let [final (last operations)]]
                  [:<= [:+ (:start-var final) (:duration final)] :makespan])]
    {:variables (into {:makespan [0 horizon]}
                      (map (fn [{:keys [start-var]}] [start-var [0 horizon]]))
                      all)
     :intervals (into {}
                      (map (fn [{:keys [interval start-var duration]}]
                             [interval {:start start-var :size duration}]))
                      all)
     :constraints (vec (concat precedences no-overlaps endings))
     :objective [:minimize :makespan]}))

(m/=> ->cp-sat [:=> [:cat Problem] cp-model/Problem])

(defn decode
  "Translate a CP-SAT solution back to a per-machine timetable."
  [problem {:keys [status values]}]
  (if values
    {:status status
     :makespan (values :makespan)
     :machines (update-vals
                (group-by :machine (tasks problem))
                (fn [on-machine]
                  (->> on-machine
                       (map (fn [{:keys [job position duration start-var]}]
                              {:job job
                               :position position
                               :start (values start-var)
                               :end (+ (values start-var) duration)}))
                       (sort-by :start)
                       vec)))}
    {:status status}))

(m/=> decode [:=> [:cat Problem [:map [:status schema/Status]
                                 [:values {:optional true} [:map-of :keyword :int]]]]
              Solution])

(defn solve
  "Solve `problem`, returning a Result of its Solution."
  ([problem] (solve problem {}))
  ([problem options]
   (r/let-ok [valid (schema/conform Problem :scheduling/invalid-problem problem)
              solution (cp-sat/solve (->cp-sat valid) options)]
     (r/ok (decode valid solution)))))

(m/=> solve [:function
             [:=> [:cat :any] (schema/result-of Solution)]
             [:=> [:cat :any :any] (schema/result-of Solution)]])
