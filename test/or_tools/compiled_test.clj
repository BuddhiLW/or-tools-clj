(ns or-tools.compiled-test
  "Bin packing and job-shop scheduling: problems compiled to CP-SAT."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [or-tools.bin-packing :as bin-packing]
            [or-tools.cp-sat.model :as cp-model]
            [or-tools.scheduling :as scheduling]
            [or-tools.support :as support]))

(support/instrument!)

(def documented-bins
  {:capacity 100
   :items (zipmap (map #(keyword (str "i" %)) (range))
                  [48 30 19 36 36 27 42 42 36 24 30])})

(def small-bins
  {:capacity 10 :items {:a 6 :b 5 :c 4}})

(def gen-bins
  (gen/let [weights (gen/vector (gen/choose 0 10) 1 5)
            capacity (gen/choose 10 15)]
    {:capacity capacity
     :items (zipmap (map #(keyword (str "i" %)) (range)) weights)}))

(defn packing-sound?
  "True when every item lands in exactly one bin and no bin exceeds capacity."
  [{:keys [items capacity] :as problem}]
  (let [{:keys [bins bins-used]} (:ok (bin-packing/solve problem {:workers 1}))]
    (and (= (sort (map str (keys items))) (sort (map str (mapcat :items bins))))
         (every? #(<= (:load %) capacity) bins)
         (= bins-used (count bins))
         (every? (fn [{:keys [items load]}]
                   (= load (reduce + (map (:items problem) items))))
                 bins))))

(deftrifecta packings-are-sound or-tools.compiled-test/packing-sound?
  {:gen gen-bins
   :pred true?
   :num-tests 25})

(deftrifecta placed or-tools.bin-packing/placed
  {:golden-path "test/golden/bin-packing/placed.edn"
   :apply? true
   :cases {:first [0 0] :other [3 7]}
   :mutations [["collides" (fn [_ _] :placed/x)]]})

(deftrifecta used or-tools.bin-packing/used
  {:golden-path "test/golden/bin-packing/used.edn"
   :cases {:first 0 :other 7}
   :gen gen/nat
   :pred keyword?
   :num-tests 20
   :mutations [["collides" (fn [_] :used/x)]]})

(deftrifecta ordered-items or-tools.bin-packing/ordered-items
  {:golden-path "test/golden/bin-packing/ordered-items.edn"
   :cases {:mixed {:b 1 "a" 2 :c 3}}
   :gen (gen/fmap :items gen-bins)
   :pred vector?
   :num-tests 20
   :mutations [["reversed" (fn [items] (vec (reverse (sort-by (comp str first) items))))]]})

(deftrifecta bin-packing-compile or-tools.bin-packing/->cp-sat
  {:golden-path "test/golden/bin-packing/to-cp-sat.edn"
   :cases {:small small-bins
           :limited (assoc small-bins :max-bins 2)}
   :gen gen-bins
   :pred (support/valid cp-model/Problem)
   :num-tests 20
   :mutations [["no-objective" (fn [p] (dissoc (bin-packing/->cp-sat p) :objective))]]})

(def bin-values
  {:placed/item0-bin0 1 :placed/item0-bin1 0 :placed/item1-bin0 0 :placed/item1-bin1 1 :used/bin0 1 :used/bin1 1})

(deftrifecta bin-packing-decode or-tools.bin-packing/decode
  {:golden-path "test/golden/bin-packing/decode.edn"
   :apply? true
   :cases {:solved [{:capacity 9 :items {:a 2 :b 3} :max-bins 2}
                    {:status :optimal :values bin-values}]
           :infeasible [{:capacity 9 :items {:a 2}} {:status :infeasible}]}
   :mutations [["no-bins" (fn [_ {:keys [status]}] {:status status})]]})

(deftrifecta bin-packing-solve or-tools.bin-packing/solve
  {:golden-path "test/golden/bin-packing/solve.edn"
   :xf (fn [result] (or (:error result) (select-keys (:ok result) [:status :bins-used])))
   :cases {:small small-bins
           :oversize {:capacity 1 :items {:a 5}}
           :invalid {:capacity -1 :items {}}}
   :mutations [["always-infeasible" (fn [& _] {:ok {:status :infeasible}})]]})

(deftest documented-bin-packing-example
  (testing "the bin-packing guide uses 4 bins"
    (is (= 4 (get-in (bin-packing/solve documented-bins {:time-limit-s 30})
                     [:ok :bins-used])))))

(def documented-jobs
  {:jobs {:j0 [{:machine 0 :duration 3} {:machine 1 :duration 2} {:machine 2 :duration 2}]
          :j1 [{:machine 0 :duration 2} {:machine 2 :duration 1} {:machine 1 :duration 4}]
          :j2 [{:machine 1 :duration 4} {:machine 2 :duration 3}]}})

(def small-jobs
  {:jobs {:a [{:machine :m :duration 2} {:machine :n :duration 1}]
          :b [{:machine :m :duration 3}]}})

(def gen-jobs
  (gen/fmap (fn [jobs]
              {:jobs (zipmap [:a :b :c]
                             (map (fn [operations]
                                    (mapv (fn [[machine duration]]
                                            {:machine machine :duration duration})
                                          operations))
                                  jobs))})
            (gen/vector (gen/vector (gen/tuple (gen/elements [:m :n]) (gen/choose 1 4)) 1 3) 3)))

(defn schedule-sound?
  "True when machines never overlap and each job keeps its operation order."
  [problem]
  (let [{:keys [machines makespan]} (:ok (scheduling/solve problem {:workers 1}))
        slots (mapcat val machines)]
    (and (every? (fn [[_ on-machine]]
                   (every? (fn [[a b]] (<= (:end a) (:start b)))
                           (partition 2 1 on-machine)))
                 machines)
         (every? (fn [[_ of-job]]
                   (every? (fn [[a b]] (<= (:end a) (:start b)))
                           (partition 2 1 (sort-by :position of-job))))
                 (group-by :job slots))
         (= makespan (reduce max (map :end slots))))))

(deftrifecta schedules-are-sound or-tools.compiled-test/schedule-sound?
  {:gen gen-jobs
   :pred true?
   :num-tests 25})

(deftrifecta tasks or-tools.scheduling/tasks
  {:golden-path "test/golden/scheduling/tasks.edn"
   :cases {:small small-jobs}
   :gen gen-jobs
   :pred vector?
   :num-tests 20
   :mutations [["empty" (fn [_] [])]]})

(deftrifecta horizon-of or-tools.scheduling/horizon-of
  {:golden-path "test/golden/scheduling/horizon-of.edn"
   :cases {:summed small-jobs :explicit (assoc small-jobs :horizon 99)}
   :gen gen-jobs
   :pred pos-int?
   :num-tests 20
   :mutations [["zero" (fn [_] 0)]]})

(deftrifecta scheduling-compile or-tools.scheduling/->cp-sat
  {:golden-path "test/golden/scheduling/to-cp-sat.edn"
   :cases {:small small-jobs}
   :gen gen-jobs
   :pred (support/valid cp-model/Problem)
   :num-tests 20
   :mutations [["no-constraints" (fn [p] (assoc (scheduling/->cp-sat p) :constraints []))]]})

(deftrifecta scheduling-decode or-tools.scheduling/decode
  {:golden-path "test/golden/scheduling/decode.edn"
   :apply? true
   :cases {:solved [small-jobs {:status :optimal
                                :values {:makespan 5 :start/job0-op0 0 :start/job0-op1 4 :start/job1-op0 2}}]
           :infeasible [small-jobs {:status :infeasible}]}
   :mutations [["no-timetable" (fn [_ {:keys [status]}] {:status status})]]})

(deftrifecta scheduling-solve or-tools.scheduling/solve
  {:golden-path "test/golden/scheduling/solve.edn"
   :xf (fn [result] (or (:error result) (select-keys (:ok result) [:status :makespan])))
   :cases {:small small-jobs
           :too-tight (assoc small-jobs :horizon 1)
           :invalid {:jobs {}}}
   :mutations [["always-infeasible" (fn [& _] {:ok {:status :infeasible}})]]})

(deftest documented-job-shop-example
  (testing "the job-shop guide reports a makespan of 11"
    (is (= 11 (get-in (scheduling/solve documented-jobs) [:ok :makespan])))))
