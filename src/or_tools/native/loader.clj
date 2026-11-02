(ns or-tools.native.loader
  "Loads the OR-Tools native library once per JVM."
  (:require [malli.core :as m])
  (:import [com.google.ortools Loader]))

(defonce ^:private loaded
  (delay (Loader/loadNativeLibraries) true))

(defn ensure-loaded!
  "Load the native library if this JVM has not loaded it yet. Return true."
  []
  @loaded)

(m/=> ensure-loaded! [:=> [:cat] [:= true]])
