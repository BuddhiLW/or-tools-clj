(ns or-tools.kernel.schema
  "Shared value objects and validation for every or-tools bounded context."
  (:require [hive-dsl.result :as r]
            [malli.core :as m]
            [malli.error :as me]))

(defn finite-number?
  "True when `x` is a number that is neither NaN nor infinite."
  [x]
  (and (number? x)
       (not (and (float? x) (or (Double/isNaN x) (Double/isInfinite x))))))

(m/=> finite-number? [:=> [:cat :any] :boolean])

(def Real
  "A finite number."
  [:fn {:error/message "must be a finite number"
        :gen/schema [:int {:min -1000 :max 1000}]}
   finite-number?])

(def NonNegInt
  "An integer at or above zero."
  [:int {:min 0 :gen/max 1000}])

(def PosInt
  "An integer at or above one."
  [:int {:min 1 :gen/max 1000}])

(def PosReal
  "A finite number above zero."
  [:and Real [:> 0]])

(def Label
  "A caller-chosen identifier of a node, worker, task or item."
  [:or :keyword :string :int])

(def Status
  "An outcome keyword reported by a solver."
  :keyword)

(def ErrorResult
  "A hive-dsl error Result."
  [:map [:error :keyword]])

(defn result-of
  "Build the schema of a hive-dsl Result holding `ok-schema`."
  [ok-schema]
  [:or [:map {:closed true} [:ok ok-schema]] ErrorResult])

(m/=> result-of [:=> [:cat :any] vector?])

(defn instance-of
  "Build a schema accepting instances of `klass`."
  [klass]
  [:fn {:error/message (str "must be an instance of " klass)}
   (fn [x] (instance? klass x))])

(m/=> instance-of [:=> [:cat [:fn class?]] vector?])

(defn conform
  "Return an ok Result of `value` when it matches `schema`, otherwise an error
   Result of `category` carrying the humanized explanation under :explain."
  [schema category value]
  (if-let [explanation (m/explain schema value)]
    (r/err category {:explain (me/humanize explanation)})
    (r/ok value)))

(m/=> conform [:=> [:cat :any :keyword :any] [:or [:map [:ok :any]] ErrorResult]])
