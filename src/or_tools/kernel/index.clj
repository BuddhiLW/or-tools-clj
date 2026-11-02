(ns or-tools.kernel.index
  "Mapping between caller labels and the dense integers native solvers require."
  (:require [malli.core :as m]
            [or-tools.kernel.schema :as schema]))

(defn labels->index
  "Return a label -> position map following first appearance in `labels`."
  [labels]
  (reduce (fn [index label]
            (if (contains? index label)
              index
              (assoc index label (count index))))
          {}
          labels))

(m/=> labels->index [:=> [:cat [:sequential schema/Label]]
                     [:map-of schema/Label :int]])

(defn index->labels
  "Return the vector of labels ordered by their position in `index`."
  [index]
  (mapv key (sort-by val index)))

(m/=> index->labels [:=> [:cat [:map-of schema/Label :int]]
                     [:vector schema/Label]])
