(ns fractl.lang.tools.util
  (:require [clojure.string :as s]))  

#?(:clj
   (defn get-system-model-paths []
     (if-let [paths (System/getenv "FRACTL_MODEL_PATHS")]
       (s/split paths #":")
       ["."])))
