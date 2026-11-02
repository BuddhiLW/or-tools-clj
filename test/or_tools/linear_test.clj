(ns or-tools.linear-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.walk]
            [hive-test.trifecta :refer [deftrifecta]]
            [or-tools.linear :as linear]
            [or-tools.linear.model :as model]
            [or-tools.support :as support]))

(support/instrument!)

(def stigler-style
  {:variables {:x {:lb 0} :y {:lb 0}}
   :constraints [[:<= [:+ :x [:* 2 :y]] 14]
                 [:>= [:* 3 :x] :y]
                 [:<= [:- :x :y] 2]]
   :objective [:maximize [:+ [:* 3 :x] [:* 4 :y]]]})

(def gen-bounded-problem
  (gen/fmap (fn [[upper-x upper-y limit a b]]
              {:variables {:x {:lb 0 :ub upper-x} :y {:lb 0 :ub upper-y}}
               :constraints [[:<= [:+ :x :y] limit]]
               :objective [:maximize [:+ [:* a :x] [:* b :y]]]})
            (gen/tuple (gen/choose 0 9) (gen/choose 0 9) (gen/choose 0 12)
                       (gen/choose -3 3) (gen/choose -3 3))))

(def gen-relation
  (gen/elements [[:<= [:+ :x 1] 3]
                 [:>= :x [:* 2 :y]]
                 [:= :x 1.5]
                 [:between 0 [:+ :x 2] 5]]))

(defn rounded
  "Round every number in a Result to 6 decimals so snapshots survive solver noise."
  [result]
  (clojure.walk/postwalk
   (fn [x] (if (float? x) (/ (Math/round (* 1e6 (double x))) 1e6) x))
   (support/without-stats result)))

(deftrifecta promote-constraint or-tools.linear.model/promote-constraint
  {:golden-path "test/golden/linear/promote-constraint.edn"
   :cases {:le [:<= [:+ :x 1] 3]
           :ge [:>= :x [:* 2 :y]]
           :eq [:= :x 1.5]
           :between [:between 0 [:+ :x 2] 5]}
   :gen gen-relation
   :pred (support/valid model/PlanConstraint)
   :num-tests 20
   :mutations [["open-range" (fn [_] {:terms {} :lb ##-Inf :ub ##Inf})]
               ["loses-constant" (fn [c] (assoc (model/promote-constraint c) :ub 0.0))]]})

(deftrifecta unknown-variables or-tools.linear.model/unknown-variables
  {:golden-path "test/golden/linear/unknown-variables.edn"
   :cases {:resolved stigler-style
           :missing {:variables {:x {}} :constraints [[:<= :nope 1]] :objective [:minimize :other]}}
   :gen gen-bounded-problem
   :pred empty?
   :num-tests 20
   :mutations [["none" (fn [_] #{})]]})

(deftrifecta build-plan or-tools.linear.model/build-plan
  {:golden-path "test/golden/linear/build-plan.edn"
   :cases {:default-solver stigler-style
           :mip {:solver :scip :variables {:b :bool :n {:lb 0 :integer? true}}}}
   :gen gen-bounded-problem
   :pred (support/valid model/Plan)
   :num-tests 20
   :mutations [["wrong-solver" (fn [p] (assoc (model/build-plan p) :solver-id "BOP"))]]})

(deftrifecta promote or-tools.linear.model/promote
  {:golden-path "test/golden/linear/promote.edn"
   :cases {:ok stigler-style
           :unknown {:variables {} :constraints [[:<= :nope 1]]}
           :malformed {:variables {:x :wat}}
           :bad-solver {:solver :abacus :variables {}}}
   :gen gen-bounded-problem
   :pred :ok
   :num-tests 20
   :mutations [["never-fails" (fn [p] {:ok p})]]})

(deftrifecta promote-options or-tools.linear.model/promote-options
  {:golden-path "test/golden/linear/promote-options.edn"
   :cases {:ok {:time-limit-s 1 :threads 2}
           :unknown {:speed 1}}
   :gen (support/gen-of model/Options)
   :pred :ok
   :num-tests 20
   :mutations [["never-fails" (fn [o] {:ok o})]]})

(deftrifecta status-keyword or-tools.linear.model/status-keyword
  {:golden-path "test/golden/linear/status-keyword.edn"
   :cases {:optimal "OPTIMAL" :feasible "FEASIBLE" :infeasible "INFEASIBLE"
           :unbounded "UNBOUNDED" :abnormal "ABNORMAL" :invalid "MODEL_INVALID"
           :not-solved "NOT_SOLVED" :other "?"}
   :gen gen/string-alphanumeric
   :pred keyword?
   :mutations [["always-optimal" (fn [_] :optimal)]]})

(deftrifecta solved or-tools.linear.model/solved?
  {:golden-path "test/golden/linear/solved.edn"
   :cases {:optimal :optimal :feasible :feasible :unbounded :unbounded}
   :gen (gen/elements [:optimal :feasible :unbounded :abnormal])
   :pred boolean?
   :mutations [["always" (fn [_] true)]]})

(deftrifecta solve or-tools.linear/solve
  {:golden-path "test/golden/linear/solve.edn"
   :xf rounded
   :cases {:documented-lp stigler-style
           :infeasible {:variables {:x {:lb 0 :ub 1}} :constraints [[:>= :x 5]]}
           :unbounded {:variables {:x {:lb 0}} :objective [:maximize :x]}
           :invalid {:variables 3}}
   :gen gen-bounded-problem
   :pred (comp #{:optimal} :status :ok)
   :num-tests 40
   :mutations [["always-infeasible" (fn [& _] {:ok {:status :infeasible}})]]})

(deftest documented-examples
  (testing "the linear optimization guide: optimum 34 at x=6, y=4"
    (let [{:keys [status objective values]} (:ok (linear/solve stigler-style))]
      (is (= :optimal status))
      (is (< (abs (- 34.0 objective)) 1e-6))
      (is (< (abs (- 6.0 (:x values))) 1e-6))
      (is (< (abs (- 4.0 (:y values))) 1e-6))))
  (testing "the MIP guide: optimum 23 at x=3, y=2"
    (let [{:keys [status objective values]}
          (:ok (linear/solve {:solver :scip
                              :variables {:x {:lb 0 :integer? true} :y {:lb 0 :integer? true}}
                              :constraints [[:<= [:+ :x [:* 7 :y]] 17.5] [:<= :x 3.5]]
                              :objective [:maximize [:+ :x [:* 10 :y]]]}))]
      (is (= :optimal status))
      (is (< (abs (- 23.0 objective)) 1e-6))
      (is (< (abs (- 3.0 (:x values))) 1e-6))
      (is (< (abs (- 2.0 (:y values))) 1e-6)))))

(deftest lp-reports-duals-and-mip-does-not
  (is (= 3 (count (get-in (linear/solve stigler-style) [:ok :duals]))))
  (let [mip (:ok (linear/solve {:solver :scip
                                :variables {:b :bool}
                                :objective [:maximize :b]}))]
    (is (not (contains? mip :duals)))
    (is (contains? mip :best-bound))))

(deftest unavailable-backend-is-an-error
  (is (= :linear/solver-unavailable
         (:error (linear/solve {:solver :cplex :variables {:x {:lb 0}}})))))

(deftest unbounded-status-depends-on-the-backend
  (let [problem {:variables {:x {:lb 0}} :objective [:maximize :x]}
        status (fn [p options] (get-in (linear/solve p options) [:ok :status]))]
    (testing "GLOP presolve reports an unbounded model as infeasible"
      (is (= :infeasible (status problem {}))))
    (testing "GLOP without presolve tells the two apart"
      (is (= :unbounded (status problem {:solver-parameters "use_preprocessing: false"}))))
    (testing "SCIP reports it as unbounded"
      (is (= :unbounded (status (assoc problem :solver :scip) {}))))))

(deftest rejected-solver-parameters-are-an-error
  (is (= :linear/native-failure
         (:error (linear/solve {:variables {:x {:lb 0}}}
                               {:solver-parameters "not_a_parameter: 1"})))))
