(ns or-tools.kernel.expr
  "Linear expressions as data, and their canonical linear form.

   An expression is a number, a variable keyword, [:+ e*], [:- e+] or
   [:* coefficient e]."
  (:require [malli.core :as m]))

(defn expr-schema
  "Build the schema of an expression over `coefficient` and `variable` schemas."
  [coefficient variable]
  [:schema {:registry {::expr [:or
                               coefficient
                               variable
                               [:tuple [:= :*] coefficient [:ref ::expr]]
                               [:cat [:= :+] [:* [:schema [:ref ::expr]]]]
                               [:cat [:= :-] [:+ [:schema [:ref ::expr]]]]]}}
   [:ref ::expr]])

(m/=> expr-schema [:=> [:cat :any :any] vector?])

(def AnyExpr
  "An expression of unconstrained coefficient type."
  (expr-schema number? :keyword))

(def LinearForm
  "The canonical form: a variable -> coefficient map plus a constant."
  [:map {:closed true}
   [:terms [:map-of :keyword number?]]
   [:constant number?]])

(def zero-form
  "The linear form of the number zero."
  {:terms {} :constant 0})

(defn scale
  "Multiply `form` by the number `factor`, dropping terms that reach zero."
  [factor form]
  {:terms (into {}
                (keep (fn [[variable coefficient]]
                        (let [scaled (*' factor coefficient)]
                          (when-not (zero? scaled) [variable scaled]))))
                (:terms form))
   :constant (*' factor (:constant form))})

(m/=> scale [:=> [:cat number? LinearForm] LinearForm])

(defn add
  "Sum two linear forms, dropping terms that cancel."
  [a b]
  {:terms (reduce-kv (fn [terms variable coefficient]
                       (let [total (+' (get terms variable 0) coefficient)]
                         (if (zero? total)
                           (dissoc terms variable)
                           (assoc terms variable total))))
                     (:terms a)
                     (:terms b))
   :constant (+' (:constant a) (:constant b))})

(m/=> add [:=> [:cat LinearForm LinearForm] LinearForm])

(defn subtract
  "Return the linear form of `a` minus `b`."
  [a b]
  (add a (scale -1 b)))

(m/=> subtract [:=> [:cat LinearForm LinearForm] LinearForm])

(defn linearize
  "Reduce the expression `e` to its linear form."
  [e]
  (cond
    (number? e) {:terms {} :constant e}
    (keyword? e) {:terms {e 1} :constant 0}
    :else
    (let [[operator & operands] e]
      (case operator
        :+ (reduce add zero-form (map linearize operands))
        :- (let [[head & tail] (map linearize operands)]
             (if (seq tail)
               (reduce subtract head tail)
               (scale -1 head)))
        :* (scale (first operands) (linearize (second operands)))))))

(m/=> linearize [:=> [:cat AnyExpr] LinearForm])

(defn variables
  "Return the set of variable keywords the expression `e` names."
  [e]
  (cond
    (number? e) #{}
    (keyword? e) #{e}
    :else
    (let [[operator & operands] e]
      (case operator
        :* (variables (second operands))
        (into #{} (mapcat variables) operands)))))

(m/=> variables [:=> [:cat AnyExpr] [:set :keyword]])

(defn evaluate
  "Evaluate `form` under the variable -> number map `values`."
  [form values]
  (reduce-kv (fn [total variable coefficient]
               (+' total (*' coefficient (get values variable 0))))
             (:constant form)
             (:terms form)))

(m/=> evaluate [:=> [:cat LinearForm [:map-of :keyword number?]] number?])
