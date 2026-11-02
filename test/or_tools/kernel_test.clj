(ns or-tools.kernel-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [or-tools.kernel.expr :as expr]
            [or-tools.kernel.index]
            [or-tools.kernel.schema :as schema]
            [or-tools.support :as support]))

(support/instrument!)

(def SmallExpr
  (expr/expr-schema [:int {:min -9 :max 9}] [:enum :x :y :z]))

(def SmallForm
  [:map {:closed true}
   [:terms [:map-of [:enum :x :y :z] [:int {:min -9 :max 9}]]]
   [:constant [:int {:min -9 :max 9}]]])

(def sample-values
  {:x 3 :y -2 :z 5})

(defn interpret
  "Evaluate an expression directly, independently of the linear form."
  [e]
  (cond
    (number? e) e
    (keyword? e) (sample-values e)
    :else (let [[operator & operands] e]
            (case operator
              :+ (reduce +' 0 (map interpret operands))
              :- (if (next operands)
                   (reduce -' (map interpret operands))
                   (-' (interpret (first operands))))
              :* (*' (first operands) (interpret (second operands)))))))

(defn evaluate-linearized
  "Evaluate an expression through its linear form."
  [e]
  (expr/evaluate (expr/linearize e) sample-values))

(deftrifecta linearize-oracle or-tools.kernel-test/evaluate-linearized
  {:gen (support/gen-of SmallExpr)
   :property-type :oracle
   :oracle interpret
   :num-tests 100})

(deftrifecta finite-number or-tools.kernel.schema/finite-number?
  {:golden-path "test/golden/kernel/finite-number.edn"
   :cases {:int 3 :double 1.5 :nan ##NaN :inf ##Inf :string "1" :nil nil}
   :gen gen/any-equatable
   :pred boolean?
   :mutations [["always-true" (fn [_] true)]
               ["accepts-nan" (fn [x] (number? x))]]})

(deftrifecta result-of or-tools.kernel.schema/result-of
  {:golden-path "test/golden/kernel/result-of.edn"
   :cases {:int :int :string :string}
   :gen (gen/elements [:int :string :keyword])
   :pred vector?
   :mutations [["ok-only" (fn [s] [:map [:ok s]])]]})

(defn instance-of-accepts?
  "Report whether the instance-of schema of `klass` accepts `value`."
  [klass value]
  ((support/valid (schema/instance-of klass)) value))

(deftrifecta instance-of or-tools.kernel.schema/instance-of
  {:gen (gen/elements [String Long])
   :pred (support/valid [:cat [:= :fn] :map :any])
   :num-tests 10})

(deftrifecta instance-of-behaviour or-tools.kernel-test/instance-of-accepts?
  {:golden-path "test/golden/kernel/instance-of.edn"
   :apply? true
   :cases {:yes [String "a"] :no [String 1]}})

(deftrifecta conform or-tools.kernel.schema/conform
  {:golden-path "test/golden/kernel/conform.edn"
   :apply? true
   :cases {:valid [:int :x/bad 1]
           :invalid [:int :x/bad "one"]}
   :gen (gen/tuple (gen/return :int) (gen/return :x/bad) gen/any-equatable)
   :pred (support/valid [:or [:map [:ok :int]] [:map [:error [:= :x/bad]] [:explain :any]]])
   :mutations [["always-ok" (fn [_ _ value] {:ok value})]
               ["loses-category" (fn [_ _ _] {:error :other})]]})

(deftrifecta expr-schema or-tools.kernel.expr/expr-schema
  {:golden-path "test/golden/kernel/expr-schema.edn"
   :apply? true
   :cases {:ints [:int :keyword]}
   :mutations [["no-recursion" (fn [_ _] [:any])]]})

(deftrifecta scale or-tools.kernel.expr/scale
  {:golden-path "test/golden/kernel/scale.edn"
   :apply? true
   :cases {:double [2 {:terms {:x 3 :y -1} :constant 4}]
           :zero [0 {:terms {:x 3} :constant 4}]}
   :gen (gen/tuple gen/small-integer (support/gen-of SmallForm))
   :pred (support/valid expr/LinearForm)
   :mutations [["ignores-constant" (fn [factor form]
                                     (update-vals form (fn [v] (if (map? v) (update-vals v #(* factor %)) v))))]
               ["identity" (fn [_ form] form)]]})

(deftrifecta add or-tools.kernel.expr/add
  {:golden-path "test/golden/kernel/add.edn"
   :apply? true
   :cases {:merge [{:terms {:x 1} :constant 1} {:terms {:y 2} :constant 2}]
           :cancel [{:terms {:x 1} :constant 0} {:terms {:x -1} :constant 0}]}
   :gen (gen/tuple (support/gen-of SmallForm) (support/gen-of SmallForm))
   :pred (support/valid expr/LinearForm)
   :mutations [["first-only" (fn [a _] a)]
               ["keeps-zero" (fn [a b] {:terms (merge-with + (:terms a) (:terms b))
                                        :constant (+ (:constant a) (:constant b))})]]})

(deftrifecta subtract or-tools.kernel.expr/subtract
  {:golden-path "test/golden/kernel/subtract.edn"
   :apply? true
   :cases {:simple [{:terms {:x 3} :constant 1} {:terms {:x 1 :y 2} :constant 5}]}
   :gen (gen/tuple (support/gen-of SmallForm) (support/gen-of SmallForm))
   :pred (support/valid expr/LinearForm)
   :mutations [["adds-instead" (fn [a b] (expr/add a b))]]})

(deftrifecta linearize or-tools.kernel.expr/linearize
  {:golden-path "test/golden/kernel/linearize.edn"
   :cases {:number 7
           :variable :x
           :sum [:+ :x [:* 2 :y] 3]
           :difference [:- :x :y 1]
           :negation [:- :x]
           :empty-sum [:+]
           :cancelling [:+ :x [:* -1 :x]]
           :nested [:* 2 [:+ :x [:* 3 [:- :y 1]]]]}
   :gen (support/gen-of SmallExpr)
   :pred (support/valid expr/LinearForm)
   :mutations [["constant-zero" (fn [_] expr/zero-form)]]})

(deftrifecta variables or-tools.kernel.expr/variables
  {:golden-path "test/golden/kernel/variables.edn"
   :cases {:number 7
           :variable :x
           :nested [:+ :x [:* 2 [:- :y :z]]]
           :scaled-to-zero [:* 0 :x]}
   :gen (support/gen-of SmallExpr)
   :pred set?
   :mutations [["empty" (fn [_] #{})]]})

(deftrifecta evaluate or-tools.kernel.expr/evaluate
  {:golden-path "test/golden/kernel/evaluate.edn"
   :apply? true
   :cases {:simple [{:terms {:x 2 :y 3} :constant 1} {:x 10 :y 100}]
           :absent [{:terms {:x 2} :constant 1} {}]}
   :gen (gen/tuple (support/gen-of SmallForm) (gen/return sample-values))
   :pred number?
   :mutations [["ignores-constant" (fn [form values]
                                     (expr/evaluate (assoc form :constant 0) values))]]})

(deftrifecta labels-to-index or-tools.kernel.index/labels->index
  {:golden-path "test/golden/kernel/labels-to-index.edn"
   :cases {:empty []
           :repeats [:a :b :a :c]
           :mixed [:a "b" 3]}
   :gen (gen/vector (gen/elements [:a :b "c" 1 2]))
   :pred (support/valid [:map-of schema/Label :int])
   :mutations [["reversed" (fn [labels] (zipmap (reverse (distinct labels)) (range)))]]})

(deftrifecta index-to-labels or-tools.kernel.index/index->labels
  {:golden-path "test/golden/kernel/index-to-labels.edn"
   :cases {:empty {}
           :ordered {:b 1 :a 0 :c 2}}
   :gen (gen/fmap #(zipmap (distinct %) (range)) (gen/vector (gen/elements [:a :b :c])))
   :pred vector?
   :mutations [["reversed" (fn [index] (vec (reverse (map key (sort-by val index)))))]]})
