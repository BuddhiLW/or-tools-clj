(ns or-tools.bin-packing
  "Bin packing, compiled to a CP-SAT problem.

   (solve {:items {:a 48 :b 30 :c 19} :capacity 100})"
  (:require [hive-dsl.result :as r]
            [malli.core :as m]
            [or-tools.cp-sat :as cp-sat]
            [or-tools.cp-sat.model :as cp-model]
            [or-tools.kernel.schema :as schema]))

(def Item
  "An item identifier."
  [:or :keyword :string])

(def Problem
  "A bin-packing problem. :max-bins defaults to one bin per item."
  [:map {:closed true}
   [:items [:map-of {:min 1} Item schema/NonNegInt]]
   [:capacity schema/NonNegInt]
   [:max-bins {:optional true} schema/PosInt]])

(def Bin
  "One used bin."
  [:map {:closed true}
   [:bin :int]
   [:items [:vector Item]]
   [:load :int]])

(def Solution
  "The outcome of a bin-packing problem."
  [:map
   [:status schema/Status]
   [:bins-used {:optional true} :int]
   [:bins {:optional true} [:vector Bin]]])

(defn placed
  "Return the name of the variable placing the item at `position` in `bin`."
  [position bin]
  (keyword "placed" (str "item" position "-bin" bin)))

(m/=> placed [:=> [:cat :int :int] :keyword])

(defn used
  "Return the name of the variable marking `bin` as used."
  [bin]
  (keyword "used" (str "bin" bin)))

(m/=> used [:=> [:cat :int] :keyword])

(defn ordered-items
  "Return the [item weight] pairs of `items` in a deterministic order."
  [items]
  (vec (sort-by (comp str first) items)))

(m/=> ordered-items [:=> [:cat [:map-of Item schema/NonNegInt]]
                     [:vector [:tuple Item schema/NonNegInt]]])

(defn ->cp-sat
  "Compile a bin-packing problem to a CP-SAT problem minimizing the bins used."
  [{:keys [items capacity max-bins]}]
  (let [weights (mapv second (ordered-items items))
        positions (range (count weights))
        bins (range (or max-bins (count weights)))]
    {:variables (into {}
                      (for [variable (concat (map used bins)
                                             (for [p positions b bins] (placed p b)))]
                        [variable :bool]))
     :constraints (into (mapv (fn [p]
                                [:exactly-one (mapv (partial placed p) bins)])
                              positions)
                        (map (fn [b]
                               [:<=
                                (into [:+] (map (fn [p] [:* (nth weights p) (placed p b)]))
                                      positions)
                                [:* capacity (used b)]]))
                        bins)
     :objective [:minimize (into [:+] (map used) bins)]}))

(m/=> ->cp-sat [:=> [:cat Problem] cp-model/Problem])

(defn decode
  "Translate a CP-SAT solution back to bins."
  [{:keys [items max-bins]} {:keys [status values]}]
  (if values
    (let [ordered (ordered-items items)
          bin-count (or max-bins (count ordered))
          bins (vec (keep (fn [bin]
                            (let [contents (filterv (fn [p] (= 1 (values (placed p bin))))
                                                    (range (count ordered)))]
                              (when (seq contents)
                                {:bin bin
                                 :items (mapv #(first (nth ordered %)) contents)
                                 :load (reduce + (map #(second (nth ordered %)) contents))})))
                          (range bin-count)))]
      {:status status :bins-used (count bins) :bins bins})
    {:status status}))

(m/=> decode [:=> [:cat Problem [:map [:status schema/Status]
                                 [:values {:optional true} [:map-of :keyword :int]]]]
              Solution])

(defn solve
  "Solve `problem`, returning a Result of its Solution."
  ([problem] (solve problem {}))
  ([problem options]
   (r/let-ok [valid (schema/conform Problem :bin-packing/invalid-problem problem)
              solution (cp-sat/solve (->cp-sat valid) options)]
     (r/ok (decode valid solution)))))

(m/=> solve [:function
             [:=> [:cat :any] (schema/result-of Solution)]
             [:=> [:cat :any :any] (schema/result-of Solution)]])
