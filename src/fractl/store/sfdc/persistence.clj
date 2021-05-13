(ns fractl.store.sfdc.persistence
  "Local data storage for SFDC objects"
  (:require [clojure.data.xml :as xml]
            [clojure.string :as s]
            [org.httpkit.client :as http]
            [cheshire.core :as json]
            [taoensso.timbre :as log]
            [fractl.util :as u]
            [fractl.component :as cn]
            [fractl.lang.internal :as li]
            [fractl.store.sfdc.metadata-types :as mt]
            [fractl.store.sfdc.format :as fmt])
  (:import [java.io File FilenameFilter]
           [com.sforce.soap.metadata CustomObject CustomField
            FieldType DeploymentStatus SharingModel Profile Metadata
            ProfileFieldLevelSecurity]
           [fractl.filesystem Util]
           [fractl.filesystem Zip]))

(def ^:private storage-root "unpackaged")

(def ^:private path-sep File/separator)
(def ^:private path-sep-re-pattern (re-pattern path-sep))

(def manifest-file-name "package.xml")
(def ^:private deploy-root-path "deploy")

(defn- generic-io-config
  ([type-name pluralize parser]
   (let [extn (str "." type-name)]
     {:extn extn
      :folder-path (str storage-root path-sep
                        (if pluralize
                          (str type-name "s")
                          type-name))
      :file-name #(str (:FullName %) extn)
      :meta-dissoc #(dissoc % :Id :FullName)
      :parser parser}))
  ([type-name parser]
   (generic-io-config type-name true parser)))

(def ^:private io-config
  {:Role (generic-io-config "role" fmt/parse-role)
   :Profile (generic-io-config "profile" fmt/parse-profile)
   :SecuritySettings (generic-io-config "settings" false fmt/parse-security-settings)})

(defn- object-file-extension [recname]
  (get-in io-config [recname :extn]))

(defn- folder-path [recname repo-dir]
  (str repo-dir path-sep (get-in io-config [recname :folder-path])))

(defn- dissoc-meta-fields [recname inst]
  ((get-in io-config [recname :meta-dissoc]) inst))

(defn- object-file-name [recname inst]
  ((get-in io-config [recname :file-name]) inst))

(defn- instance-parser [recname]
  (get-in io-config [recname :parser]))

(def ^:private journal-file "sfdc-metadata.journal")

(defn- read-journal-entries []
  (try
    (read-string (slurp journal-file))
    (catch Exception _
      {})))

(defn- make-journal-entry [recname file-path]
  (let [entries (read-journal-entries)
        reclog (get entries recname #{})
        updated-entries (assoc entries recname
                               (conj reclog file-path))]
    (spit journal-file updated-entries)
    recname))

(defn- write-object-file [recname folder repo-dir inst]
  (let [file-name (object-file-name recname inst)
        full-path (str folder path-sep file-name)
        xml (fmt/instance-as-xml recname (dissoc-meta-fields recname inst))]
    ;; TODO: Make these steps atomic.
    (spit full-path xml)
    (make-journal-entry recname full-path)))

(defn write-object [entity-name instances repo-dir]
  (let [[_ n] (li/split-path entity-name)
        folder (folder-path n repo-dir)
        outfn (partial write-object-file n folder repo-dir)]
    (doseq [inst instances]
      (outfn inst))
    instances))

(defn- predicate-from-conditions [conditions]
  (let [n (second conditions)
        v (nth conditions 2)]
    ;; TODO: support more operators, including logical and/or.
    (case (first conditions)
      := #(= (get % n) v)
      (u/throw-ex (str "unsupported operation - " conditions)))))

(defn- make-file-extension-filter [^String extn]
  (reify FilenameFilter
    (accept [this f file-name]
      (.endsWith file-name extn))))

(defn- load-all-objects [[_ recname :as full-recname] repo-dir]
  (let [^File folder (File. (folder-path recname repo-dir))
        files (.listFiles
               folder
               (make-file-extension-filter
                (object-file-extension recname)))
        parse (partial (instance-parser recname) full-recname)]
    (map
     #(let [s (str %)] (parse s (slurp s)))
     files)))

(def ^:private singleton-objects {:SecuritySettings "Security.settings"})

(defn- load-singleton [[_ recname :as full-recname] repo-dir]
  (let [file-name (str (folder-path recname repo-dir) path-sep
                       (recname singleton-objects))]
    ((instance-parser recname) full-recname file-name (slurp file-name))))

(defn- singleton? [recname]
  (contains? singleton-objects recname))

(defn filter-records [full-recname conditions repo-dir]
  (if (singleton? (second full-recname))
    (load-singleton full-recname repo-dir)
    (filter (predicate-from-conditions conditions)
            (load-all-objects full-recname repo-dir))))

(defn- members-tag [n]
  (let [s (str (if (li/name? n)
                 (subs (str (second (li/split-path n))) 1)
                 n))]
    {:tag :members :content [s]}))

(def ^:private all-members (members-tag "*"))

(defn- types-tag [opt]
  (let [has-query (seqable? opt)
        [a b] (li/split-path (if has-query (first opt) opt))
        m (or b a)
        n (name m)]
    {:tag :types :content
     (concat
      (if has-query
        (concat [{:tag :name :content [n]}]
                (let [s (second opt)]
                  (if (seqable? s)
                    (map members-tag s)
                    [(members-tag s)])))
        [{:tag :name :content [n]} all-members]))}))

(defn- manifest-from-options [options]
  (let [options (or (:types options) mt/type-names)
        vers (u/getenv "SFDC_METADATA_API_VERSION" "51.0")
        content (concat (map types-tag options)
                        [{:tag :version :content [vers]}])]
    {:tag :Package :attrs
     {:xmlns (u/getenv
              "SFDC_METADATA_SCHEMA_URL"
              "http://soap.sforce.com/2006/04/metadata")}
     :content content}))

(defn write-manifest!
  ([options root-path]
   (Util/maybeCreateDirectories root-path)
   (let [xml (manifest-from-options options)]
     (with-open [out-file (java.io.FileWriter.
                           (str root-path path-sep manifest-file-name))]
       (xml/emit xml out-file))))
  ([options]
   (write-manifest! options ".")))

(defn- deploy-folder-name [repo-dir]
  (str "." path-sep deploy-root-path))

(defn process-type-names [ks]
  (map #(if (= % :SecuritySettings)
          [:Settings ["Security"]]
          %)
       ks))

(defn prepare-deploy-package [repo-dir]
  (when-let [journal (seq (read-journal-entries))]
    (let [df (deploy-folder-name repo-dir)]
      (write-manifest! {:types (process-type-names (keys journal))} df)
      (doseq [vs (vals journal)]
        (doseq [src vs]
          (let [parts (s/split src path-sep-re-pattern)
                folder (s/join
                        path-sep
                        (concat [df] (take-last 1 (drop-last parts))))
                dest (str folder path-sep (last parts))]
            (Util/maybeCreateDirectories folder)
            (Util/copyOrReplaceFile src dest))))
      (Zip/zipFolder df))))

(defn finalize-deploy [deploy-package-name repo-dir]
  (and (Util/forceDeleteDirectory (deploy-folder-name repo-dir))
       (Util/deleteFile deploy-package-name)
       (Util/deleteFile journal-file)))

(defn init-local-store [package-file repo-dir]
  (Zip/unzip package-file repo-dir)
  (Util/deleteFile package-file)
  (Util/deleteFile manifest-file-name)
  true)

(defn- attribute-type-to-field-type [atype]
  (case atype
    :Kernel/Int FieldType/Number
    :Kernel/Double FieldType/Currency
    :Kernel/DateTime FieldType/DateTime
    :Kernel/Boolean FieldType/Checkbox
    FieldType/Text))

(defn- attributes-as-fields [attrs]
  (when (seq attrs)
    (let [result (make-array CustomField (count attrs))]
      (loop [i 0, attrs attrs]
        (if-let [[aname atype] (first attrs)]
          (let [fname (name aname)
                tp (attribute-type-to-field-type atype)
                cf (doto (CustomField.)
                     (.setType tp)
                     (.setDescription fname)
                     (.setLabel fname)
                     (.setFullName (str fname "__c")))]
            (cond
              (= tp FieldType/Text)
              (.setLength cf 100)

              (= tp FieldType/Checkbox)
              (.setDefaultValue cf "true")

              (= tp FieldType/Number)
              (do (.setPrecision cf 10)
                  (.setScale cf 3)))
            (aset result i cf)
            (recur (inc i) (rest attrs)))
          result)))))

(defn- update-field-permissions [connection sfdc-namespace entity-name sfdc-attrs]
  (let [full-type-name (str (name sfdc-namespace) "__" (name entity-name) "__c")
        profiles ["Admin" "Standard"]]
    (doseq [p profiles]
      (doseq [[n _] sfdc-attrs]
        (let [^Profile profile (Profile.)
              ^ProfileFieldLevelSecurity field-sec (ProfileFieldLevelSecurity.)
              fsecs (make-array ProfileFieldLevelSecurity 1)
              mtdts (make-array Metadata 1)]
          (.setFullName profile p)
          (.setCustom profile false)
          (.setField field-sec (str full-type-name "." (name n) "__c"))
          (.setEditable field-sec true)
          (.setReadable field-sec true)
          (aset fsecs 0 field-sec)
          (.setFieldPermissions profile fsecs)
          (aset mtdts 0 profile)
          (let [results (.updateMetadata connection mtdts)]
            (amap results idx _
                  (let [r (aget results idx)]
                    (when-not (.isSuccess r)
                      (u/throw-ex (str "failed to set field permissions - " r)))))))))
    entity-name))

(defn create-custom-type [connection sfdc-namespace entity-name attrs]
  (let [[_ type-name] (li/split-path entity-name)
        sfdc-attrs (dissoc attrs :Id :Name)
        fields (attributes-as-fields sfdc-attrs)
        obj-name (name type-name)
        name-cf (doto (CustomField.)
                  (.setType FieldType/Text)
                  (.setDescription "The custom object identifier field")
                  (.setLabel obj-name)
                  (.setRequired true)
                  (.setFullName (str obj-name "__c")))
        obj (doto (CustomObject.)
              (.setFullName (str obj-name "__c"))
              (.setDeploymentStatus (DeploymentStatus/Deployed))
              (.setDescription "Created by Fractl using the Metadata API")
              (.setLabel obj-name)
              (.setPluralLabel (str obj-name "s"))
              (.setSharingModel SharingModel/ReadWrite)
              (.setEnableActivities true)
              (.setNameField name-cf))]
    (when fields
      (.setFields obj fields))
    (let [results (.upsertMetadata connection (into-array CustomObject [obj]))]
      (amap results idx _
            (let [r (aget results idx)]
              (when-not (.isSuccess r)
                (u/throw-ex (str "failed to define CustomObject - " r))))))
    (update-field-permissions connection sfdc-namespace entity-name sfdc-attrs)))

(defn- type-name-from-entity-name [sfdc-namespace [_ n]]
  (str sfdc-namespace "__" (name n) "__c"))

(defn- custom-names [sfdc-namespace attrs]
  (let [r (map (fn [[k v]]
                 (if (or (= k :Name))
                   [k v]
                   [(str sfdc-namespace "__" (name k) "__c") v]))
               attrs)]
    (into {} r)))

(defn create-record [instance-name auth-token sfdc-namespace entity-name instances]
  (let [typename (type-name-from-entity-name sfdc-namespace entity-name)
        url (str "https://" instance-name "/services/data/v51.0/sobjects/" typename "/")
        hdrs {"Authorization" (str "Bearer " auth-token)
              "Content-Type" "application/json"}
        results (map #(let [attrs (custom-names
                                   sfdc-namespace
                                   (dissoc (cn/instance-attributes %) :Id))
                            opts {:body (json/generate-string attrs)
                                  :headers hdrs}]
                        (http/post url opts))
                     instances)]
    (doseq [r results]
      (let [s (:status @r)]
        (when-not (<= 200 s 201)
          (log/error @r)
          (u/throw-ex (str "SFDC create-record failed with status " s)))))
    instances))