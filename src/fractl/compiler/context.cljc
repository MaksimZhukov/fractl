(ns fractl.compiler.context
  "Context information attached to a compilation session."
  (:require [fractl.util :as u]
            [fractl.lang.internal :as li]))

(defn make []
  (u/make-cell {}))

(defn put-record!
  "A record/entity/event encounterd during the compilation
  process. This can be used to validate references downstream."  
  [ctx rec-name schema]
  (u/call-and-set ctx #(assoc @ctx rec-name schema)))

(defn fetch-record [ctx rec-name]
  (get @ctx rec-name))

(defn put-fresh-record!
  "Call put-record! if rec-name does not exist in context."
  [ctx rec-name schema]
  (if-not (fetch-record ctx rec-name)
    (u/call-and-set ctx #(assoc @ctx rec-name schema))
    ctx))

(defn bind-variable! [ctx k v]
  (u/call-and-set ctx #(assoc @ctx k v)))

(defn fetch-variable [ctx k]
  (find @ctx k))

(defn unbind-variable! [ctx k]
  (u/call-and-set ctx #(dissoc @ctx k)))

(defn lookup-record [ctx instance path]
  (if-let [r (fetch-record ctx path)]
    r
    (u/throw-ex (str "instance not found - " path))))

(defn lookup-variable [ctx k]
  (if-let [r (fetch-variable ctx k)]
    r
    (u/throw-ex (str "unbound variable - " k))))

(defn bind-compile-query-fn! [ctx r]
  (bind-variable! ctx :compile-query-fn r))

(defn fetch-compile-query-fn [ctx]
  (second (fetch-variable ctx :compile-query-fn)))

(defn add-sub-alias! [ctx alias target]
  (cond
    (vector? alias)
    (doseq [a alias]
      (add-sub-alias! ctx a target))

    (keyword? alias)
    (let [aliases (or (second (fetch-variable ctx :aliases)) {})]
      (bind-variable! ctx :aliases (assoc aliases alias [:alias target])))

    :else
    (u/throw-ex (str "invalid alias identifier - " alias))))

(defn alias-name [alias]
  (if (vector? alias)
    (keyword (str "A" (hash alias)))
    alias))

(defn add-alias!
  ([ctx nm alias]
   (let [alias-name (alias-name alias)
         aliases (or (second (fetch-variable ctx :aliases)) {})
         v (if (li/parsed-path? nm) (li/make-path nm) nm)]
     (bind-variable! ctx :aliases (assoc aliases alias-name v))
     (when (vector? alias)
       (doseq [a alias]
         (add-sub-alias! ctx a alias-name)))))
  ([ctx alias]
   (add-alias! ctx (alias-name alias) alias)))

(defn redirect? [a]
  (and (vector? a) (= :alias (first a))))

(def ^:private redirect-tag second)

(defn aliased-name
  ([ctx k follow-redirect]
   (let [aliases (second (fetch-variable ctx :aliases))]
     (when-let [a (get aliases k)]
       (if (and (redirect? a) follow-redirect)
         (aliased-name ctx (redirect-tag a))
         a))))
  ([ctx k] (aliased-name ctx k true)))
