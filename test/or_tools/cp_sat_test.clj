(ns or-tools.cp-sat-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [or-tools.cp-sat :as cp-sat]
            [or-tools.cp-sat.model :as model]
            [or-tools.kernel.expr]
            [or-tools.ports :as ports]
            [or-tools.support :as support]))

(support/instrument!)

(def SmallExpr
  (or-tools.kernel.expr/expr-schema [:int {:min -5 :max 5}] [:enum :x :y]))

(def small-problem
  {:variables {:x [0 4] :y [0 4]}
   :constraints [[:<= [:+ :x :y] 5]]
   :objective [:maximize [:+ :x [:* 2 :y]]]})

(def gen-small-problem
  (gen/fmap (fn [[upper-x upper-y limit a b]]
              {:variables {:x [0 upper-x] :y [0 upper-y]}
               :constraints [[:<= [:+ :x :y] limit]]
               :objective [:maximize [:+ [:* a :x] [:* b :y]]]})
            (gen/tuple (gen/choose 0 4) (gen/choose 0 4) (gen/choose 0 6)
                       (gen/choose -3 3) (gen/choose -3 3))))

(def gen-constraint
  (gen/one-of [(gen/fmap (fn [[e f]] [:<= e f])
                         (gen/tuple (support/gen-of SmallExpr) (support/gen-of SmallExpr)))
               (gen/return [:bool-or [:x [:not :y]]])
               (gen/return [:only-if [:x] [:only-if [[:not :y]] [:= :x 1]]])
               (gen/return [:no-overlap [:i :j]])
               (gen/return [:cumulative 2 [[:i :x]]])
               (gen/return [:circuit [[0 1 :x] [1 0 [:not :y]]]])
               (gen/return [:reservoir 0 2 [[:x 1]]])]))

(defn brute-force-optimum
  "Return the optimum of a generated problem by enumerating every assignment."
  [{:keys [variables constraints objective]}]
  (let [limit (nth (first constraints) 2)
        [_ [_ [_ a _] [_ b _]]] objective
        [_ upper-x] (:x variables)
        [_ upper-y] (:y variables)]
    (reduce max
            (for [x (range (inc upper-x))
                  y (range (inc upper-y))
                  :when (<= (+ x y) limit)]
              (+ (* a x) (* b y))))))

(defn solved-objective
  "Return the objective CP-SAT reports for a generated problem."
  [problem]
  (long (get-in (cp-sat/solve problem {:workers 1}) [:ok :objective])))

(deftrifecta solver-matches-brute-force or-tools.cp-sat-test/solved-objective
  {:gen gen-small-problem
   :property-type :oracle
   :oracle brute-force-optimum
   :num-tests 40})

(deftrifecta constraint-kind or-tools.cp-sat.model/constraint-kind
  {:golden-path "test/golden/cp-sat/constraint-kind.edn"
   :cases {:vector [:<= :x 1] :number 3 :nil nil}
   :gen gen/any-equatable
   :pred (constantly true)
   :mutations [["always-nil" (fn [_] nil)]]})

(deftrifecta literal-variable or-tools.cp-sat.model/literal-variable
  {:golden-path "test/golden/cp-sat/literal-variable.edn"
   :cases {:plain :b :negated [:not :b]}
   :gen (support/gen-of model/Literal)
   :pred keyword?
   :mutations [["identity" identity]]})

(deftrifecta slot-references or-tools.cp-sat.model/slot-references
  {:golden-path "test/golden/cp-sat/slot-references.edn"
   :apply? true
   :cases {:expr [:expr [:+ :x [:* 2 :y]]]
           :exprs [:exprs [:x [:+ :y 1]]]
           :lit [:lit [:not :b]]
           :lits [:lits [:a [:not :b]]]
           :vars [:vars [:x :y]]
           :intervals [:intervals [:i :j]]
           :rects [:rects [[:i :j]]]
           :demands [:demands [[:i :x]]]
           :arcs [:arcs [[0 1 [:not :b]]]]
           :events [:events [[:x 1]]]
           :int [:int 3]
           :domain [:domain [[0 1]]]}
   :gen (gen/tuple (gen/return :expr) (support/gen-of SmallExpr))
   :pred (support/valid model/References)
   :mutations [["empty" (fn [_ _] {:variables #{} :intervals #{}})]]})

(deftrifecta merge-references or-tools.cp-sat.model/merge-references
  {:golden-path "test/golden/cp-sat/merge-references.edn"
   :cases {:none []
           :two [{:variables #{:x} :intervals #{}}
                 {:variables #{:y} :intervals #{:i}}]}
   :gen (support/gen-of [:vector model/References])
   :pred (support/valid model/References)
   :num-tests 30
   :mutations [["first" (fn [refs] (or (first refs) {:variables #{} :intervals #{}}))]]})

(deftrifecta constraint-references or-tools.cp-sat.model/constraint-references
  {:golden-path "test/golden/cp-sat/constraint-references.edn"
   :cases {:comparison [:<= :x :y]
           :enforced [:only-if [:b] [:= :x 1]]
           :overlap [:no-overlap [:i :j]]}
   :gen gen-constraint
   :pred (support/valid model/References)
   :num-tests 50
   :mutations [["ignores-enforcement" (fn [_] {:variables #{} :intervals #{}})]]})

(deftrifecta problem-references or-tools.cp-sat.model/problem-references
  {:golden-path "test/golden/cp-sat/problem-references.edn"
   :cases {:all {:variables {}
                 :intervals {:i {:start :a :size :b :end :c :present :p}}
                 :constraints [[:<= :x 1]]
                 :objective [:minimize :o]
                 :hints {:h 1}}}
   :gen gen-small-problem
   :pred (support/valid model/References)
   :num-tests 30
   :mutations [["skips-objective" (fn [p] (model/problem-references (dissoc p :objective)))]]})

(deftrifecta unknown-references or-tools.cp-sat.model/unknown-references
  {:golden-path "test/golden/cp-sat/unknown-references.edn"
   :cases {:resolved small-problem
           :missing {:variables {:x [0 1]} :constraints [[:<= :x :nope] [:no-overlap [:i]]]}}
   :gen gen-small-problem
   :pred (fn [unknown] (every? empty? (vals unknown)))
   :num-tests 30
   :mutations [["nothing-unknown" (fn [_] {:variables #{} :intervals #{}})]]})

(deftrifecta promote-slot or-tools.cp-sat.model/promote-slot
  {:golden-path "test/golden/cp-sat/promote-slot.edn"
   :apply? true
   :cases {:expr [:expr [:+ :x 1]]
           :exprs [:exprs [:x 2]]
           :demands [:demands [[:i 2]]]
           :events [:events [[:x 1]]]
           :lit [:lit :b]}
   :mutations [["identity" (fn [_ value] value)]]})

(deftrifecta promote-constraint or-tools.cp-sat.model/promote-constraint
  {:golden-path "test/golden/cp-sat/promote-constraint.edn"
   :cases {:comparison [:<= [:+ :x 1] :y]
           :nested [:only-if [:a] [:only-if [[:not :b]] [:= :x 1]]]
           :boolean [:exactly-one [:a :b]]}
   :gen gen-constraint
   :pred (support/valid model/PlanConstraint)
   :num-tests 50
   :mutations [["drops-enforcement" (fn [c] (assoc (model/promote-constraint (if (= :only-if (first c)) (nth c 2) c)) :only-if []))]]})

(deftrifecta build-plan or-tools.cp-sat.model/build-plan
  {:golden-path "test/golden/cp-sat/build-plan.edn"
   :cases {:small small-problem
           :full {:variables {:b :bool :d {:domain [[0 1] [5 6]]} :s [0 9]}
                  :intervals {:i {:start :s :size 2}
                              :j {:start :s :size 2 :end [:+ :s 2] :present :b}}
                  :constraints [[:no-overlap [:i :j]]]
                  :hints {:s 1}}}
   :gen gen-small-problem
   :pred (support/valid model/Plan)
   :num-tests 30
   :mutations [["no-objective" (fn [p] (assoc (model/build-plan p) :objective nil))]]})

(deftrifecta promote or-tools.cp-sat.model/promote
  {:golden-path "test/golden/cp-sat/promote.edn"
   :cases {:ok small-problem
           :unknown {:variables {:x [0 1]} :constraints [[:<= :nope 1]]}
           :malformed {:variables {:x [0 1]} :constraints [[:wat :x]]}
           :not-a-map 3}
   :gen gen-small-problem
   :pred :ok
   :num-tests 30
   :mutations [["never-fails" (fn [p] {:ok p})]]})

(deftrifecta promote-options or-tools.cp-sat.model/promote-options
  {:golden-path "test/golden/cp-sat/promote-options.edn"
   :cases {:ok {:time-limit-s 1 :workers 2 :seed 3}
           :unknown {:speed :fast}
           :negative {:workers -1}}
   :gen (support/gen-of model/Options)
   :pred :ok
   :num-tests 30
   :mutations [["never-fails" (fn [o] {:ok o})]]})

(deftrifecta status-keyword or-tools.cp-sat.model/status-keyword
  {:golden-path "test/golden/cp-sat/status-keyword.edn"
   :cases {:optimal "OPTIMAL" :feasible "FEASIBLE" :infeasible "INFEASIBLE"
           :invalid "MODEL_INVALID" :unknown "UNKNOWN" :other "UNRECOGNIZED"}
   :gen gen/string-alphanumeric
   :pred keyword?
   :mutations [["always-optimal" (fn [_] :optimal)]]})

(deftrifecta solved or-tools.cp-sat.model/solved?
  {:golden-path "test/golden/cp-sat/solved.edn"
   :cases {:optimal :optimal :feasible :feasible :infeasible :infeasible :unknown :unknown}
   :gen (gen/elements [:optimal :feasible :infeasible :unknown])
   :pred boolean?
   :mutations [["always" (fn [_] true)]]})

(def base-variables
  {:a [0 3] :b [0 3] :c [0 3] :p :bool :q :bool})

(def base-intervals
  {:i {:start :a :size 1} :j {:start :b :size 1}})

(def constraint-samples
  "One satisfiable sample of every constraint kind."
  {:<= [:<= :a 3]
   :>= [:>= :a 0]
   :< [:< :a 4]
   :> [:> :a -1]
   := [:= :a :a]
   :!= [:!= :a 9]
   :in [:in :a [[0 1] [2 3]]]
   :all-different [:all-different [:a :b]]
   :bool-or [:bool-or [:p :q]]
   :bool-and [:bool-and [:p]]
   :bool-xor [:bool-xor [:p :q]]
   :at-most-one [:at-most-one [:p :q]]
   :at-least-one [:at-least-one [:p :q]]
   :exactly-one [:exactly-one [:p :q]]
   :implies [:implies :p [:not :q]]
   :element [:element :a [0 1 2 3] :b]
   :allowed [:allowed [:a :b] [[0 1] [1 2]]]
   :forbidden [:forbidden [:a :b] [[0 0]]]
   :min= [:min= :c [:a :b]]
   :max= [:max= :c [:a :b]]
   :abs= [:abs= :c [:- :a :b]]
   :mul= [:mul= :c :a :b]
   :div= [:div= :c :a 2]
   :mod= [:mod= :c :a 2]
   :no-overlap [:no-overlap [:i :j]]
   :no-overlap-2d [:no-overlap-2d [[:i :j] [:j :i]]]
   :cumulative [:cumulative 2 [[:i 1] [:j 1]]]
   :circuit [:circuit [[0 1 :p] [1 0 :p] [0 0 [:not :p]] [1 1 [:not :p]]]]
   :multi-circuit [:multi-circuit [[0 1 :p] [1 0 :p]]]
   :inverse [:inverse [:a :b] [:a :b]]
   :automaton [:automaton [:a :b] 0 [1] [[0 1 0] [0 0 1] [1 1 0] [1 1 1]]]
   :reservoir [:reservoir 0 2 [[:a 1] [:b 1]]]})

(deftest every-constraint-kind-reaches-the-native-solver
  (testing "the samples cover exactly the declared kinds"
    (is (= (set (keys model/constraint-shapes)) (set (keys constraint-samples)))))
  (doseq [[kind sample] constraint-samples]
    (testing kind
      (let [result (cp-sat/solve {:variables base-variables
                                  :intervals base-intervals
                                  :constraints [sample]}
                                 {:workers 1})]
        (is (contains? #{:optimal :feasible} (get-in result [:ok :status])) (pr-str result))))))

(deftest constraints-actually-constrain
  (let [values (fn [problem] (get-in (cp-sat/solve problem {:workers 1}) [:ok :values]))]
    (testing "reified enforcement"
      (let [{:keys [p a]} (values {:variables {:p :bool :a [0 9]}
                                   :constraints [[:only-if [:p] [:= :a 7]] [:= :p 1]]})]
        (is (= [1 7] [p a]))))
    (testing "domains with holes"
      (let [result (cp-sat/solve-all {:variables {:a {:domain [[0 1] [5 5]]}}} {:workers 1})]
        (is (= #{{:a 0} {:a 1} {:a 5}} (set (get-in result [:ok :solutions]))))))
    (testing "optional intervals may overlap when absent"
      (let [{:keys [a b]} (values {:variables {:a [0 0] :b [0 0] :p :bool}
                                   :intervals {:i {:start :a :size 2}
                                               :j {:start :b :size 2 :present :p}}
                                   :constraints [[:no-overlap [:i :j]]]})]
        (is (= [0 0] [a b]))))))

(deftest documented-examples
  (testing "the N-queens guide reports 92 solutions for n=8"
    (let [rows (mapv #(keyword (str "q" %)) (range 8))
          problem {:variables (zipmap rows (repeat [0 7]))
                   :constraints [[:all-different rows]
                                 [:all-different (vec (map-indexed (fn [i q] [:+ q i]) rows))]
                                 [:all-different (vec (map-indexed (fn [i q] [:- q i]) rows))]]}]
      (is (= 92 (count (get-in (cp-sat/solve-all problem {:workers 1}) [:ok :solutions]))))))
  (testing "the CP-SAT primer optimum"
    (let [result (cp-sat/solve {:variables {:x [0 7] :y [0 7] :z [0 7]}
                                :constraints [[:<= [:+ [:* 2 :x] [:* 7 :y] [:* 3 :z]] 50]
                                              [:<= [:+ [:* 3 :x] [:* -5 :y] [:* 7 :z]] 45]
                                              [:<= [:+ [:* 5 :x] [:* 2 :y] [:* -6 :z]] 37]]
                                :objective [:maximize [:+ [:* 2 :x] [:* 2 :y] [:* 3 :z]]]})]
      (is (= 35.0 (get-in result [:ok :objective]))))))

(deftest solution-limit-stops-enumeration
  (let [result (cp-sat/solve {:variables {:a [0 9]}} {:solution-limit 1 :workers 1
                                                      :all-solutions? true})]
    (is (= 1 (count (get-in result [:ok :solutions]))))))

(deftest infeasible-is-a-status-not-an-error
  (is (= :infeasible (get-in (cp-sat/solve {:variables {:x [0 1]} :constraints [[:> :x 5]]})
                             [:ok :status]))))

(deftest solver-rejection-is-an-error
  (is (= :cp-sat/model-rejected
         (:error (cp-sat/solve {:variables {:x [5 1]}})))))

(defrecord RecordingEngine [calls]
  ports/ICpSatEngine
  (solve-cp-sat [_ plan options]
    (swap! calls conj {:plan plan :options options})
    {:ok {:status :optimal :stats {:wall-time-s 0.0 :conflicts 0 :branches 0}}}))

(deftest facade-reads-the-engine-port-at-call-time
  (let [calls (atom [])]
    (binding [cp-sat/*engine* (->RecordingEngine calls)]
      (is (= :optimal (get-in (cp-sat/solve small-problem {:seed 1}) [:ok :status])))
      (is (= :cp-sat/unknown-reference
             (:error (cp-sat/solve {:variables {} :constraints [[:<= :x 1]]})))))
    (is (= 1 (count @calls)))
    (is (= {:seed 1} (:options (first @calls))))))

(deftrifecta solve or-tools.cp-sat/solve
  {:golden-path "test/golden/cp-sat/solve.edn"
   :xf support/without-stats
   :cases {:optimal {:variables {:x [0 4]} :objective [:maximize :x]}
           :infeasible {:variables {:x [0 1]} :constraints [[:> :x 5]]}
           :invalid {:variables 3}}
   :gen gen-small-problem
   :pred (comp #{:optimal} :status :ok)
   :num-tests 30
   :mutations [["always-infeasible" (fn [& _] {:ok {:status :infeasible}})]]})

(deftrifecta solve-all or-tools.cp-sat/solve-all
  {:golden-path "test/golden/cp-sat/solve-all.edn"
   :xf (fn [result] (set (get-in result [:ok :solutions])))
   :cases {:pair {:variables {:a [0 1] :b [0 1]} :constraints [[:!= :a :b]]}}
   :mutations [["no-solutions" (fn [& _] {:ok {:status :optimal :solutions []}})]]})

(deftest gate-universe-is-the-shape-table
  (is (empty? (set/difference (set (keys model/slot-schemas))
                              (set (mapcat val model/constraint-shapes))))))
