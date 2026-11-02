(ns or-tools.gates-test
  "Completeness gates. Each universe is read from source files, never from the
   registry or the test suite being checked."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [or-tools.assignment]
            [or-tools.bin-packing]
            [or-tools.cp-sat]
            [or-tools.flow]
            [or-tools.knapsack]
            [or-tools.linear]
            [or-tools.routing]
            [or-tools.scheduling])
  (:import [java.io PushbackReader]))

(defn- clj-files
  "Return every Clojure source file under `root`."
  [root]
  (->> (file-seq (io/file root))
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj")))))

(defn- read-forms
  "Read the top-level forms of `file` under its own namespace."
  [file]
  (with-open [reader (PushbackReader. (io/reader file))]
    (let [ns-form (read {:read-cond :allow} reader)
          ns-name* (second ns-form)]
      (require ns-name*)
      (binding [*ns* (find-ns ns-name*)]
        (into [ns-form]
              (take-while #(not= ::eof %))
              (repeatedly #(read {:read-cond :allow :eof ::eof} reader)))))))

(defn- defined-fns
  "Return the [namespace name] of every defn and defn- in `file`."
  [file]
  (let [forms (read-forms file)
        namespace* (second (first forms))]
    (for [form forms
          :when (and (seq? form) (contains? #{'defn 'defn-} (first form)))]
      [namespace* (second form)])))

(defn- all-fns
  "Return the [namespace name] of every function under src and examples."
  []
  (set (mapcat defined-fns (concat (clj-files "src") (clj-files "examples")))))

(defn- native-namespace?
  "True for a Boundary namespace, exercised through its facade."
  [namespace*]
  (str/ends-with? (str namespace*) ".native"))

(def boundary-exempt
  "Namespaces whose functions need a live solver or the JVM native library, each
   with the reason trifecta does not target them directly."
  {'or-tools.native.loader "Loading the library has one observable effect, covered by every solver test."})

(deftest every-function-has-a-malli-contract
  (let [universe (all-fns)
        contracted (set (for [[namespace* fns] (m/function-schemas)
                              [fn-name] fns]
                          [namespace* fn-name]))]
    (testing "the walk found the source tree"
      (is (< 100 (count universe))))
    (is (empty? (set/difference universe contracted)))))

(defn- trifecta-subjects
  "Return the subject symbol of every deftrifecta under test."
  []
  (set (for [file (clj-files "test")
             form (read-forms file)
             :when (and (seq? form) (= 'deftrifecta (first form)))]
         (let [subject (nth form 2)]
           (if (and (seq? subject) (= 'var (first subject)))
             (second subject)
             subject)))))

(defn- qualified-subject
  "Resolve a subject symbol through the aliases of its test namespace to a
   [namespace name] pair."
  [subject]
  [(symbol (namespace subject)) (symbol (name subject))])

(defn- all-subjects
  "Return every trifecta subject as a [namespace name] pair."
  []
  (set (map qualified-subject (trifecta-subjects))))

(deftest every-public-pure-function-has-a-trifecta
  (let [public (for [[namespace* fn-name] (all-fns)
                     :let [target (ns-resolve namespace* fn-name)]
                     :when (and target
                                (not (:private (meta target)))
                                (not (native-namespace? namespace*))
                                (not (contains? boundary-exempt namespace*)))]
                 [namespace* fn-name])]
    (testing "the walk found public functions"
      (is (< 50 (count public))))
    (is (empty? (set/difference (set public) (all-subjects))))))
