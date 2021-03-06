(ns webjure.json-schema.validator
  "Validator for JSON schema for draft 4"
  (:require [cheshire.core :as cheshire]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-time.coerce :as time]))

(declare validate)

(defn resolve-ref [root uri]
  (cond (.startsWith uri "#") [root (get-in root (next (str/split uri #"/")))]
        :otherwise            (let [schema (io/file uri)]
                                (when (.canRead schema)
                                  (let [parsed (cheshire/parse-string (slurp schema))]
                                    [parsed parsed])))))

(defmulti validate-by-type
          "Validate JSON schema item by type. Returns a sequence of errors."
          (fn [schema data options] (get schema "type")))

(defmethod validate-by-type "object"
  [{properties  "properties"
    patterned   "patternProperties"
    additional? "additionalProperties"
    :as         schema} data options]

  (if-not (map? data)
    [{:error :wrong-type :expected :map
      :data  data}]

    (let [required (if (:draft3-required options)
                     ;; Draft 3 required is an attribute of the property schema
                     (into #{}
                           (keep (fn [[property-name property-schema]]
                                   (when (get property-schema "required")
                                     property-name))
                                 properties))

                     ;; Draft 4 has separate required attribute with a list of property names
                     (into #{} (get schema "required")))

          [errors missing] (reduce-kv (fn [[ers mis] k v]
                                        (let [k (cond-> k (keyword? k) (name))]
                                          (if-let [pschema (get properties k)]
                                            [(if-let [error (validate pschema v options)]
                                               (assoc ers k error)
                                               ers)
                                             (disj mis k)]
                                            (if-let [pschema (->> patterned
                                                                  (keep (fn [[r s]]
                                                                          (when (re-find (re-pattern r) k)
                                                                            s)))
                                                                  first)]
                                              [(if-let [error (validate pschema v options)]
                                                 (assoc ers k error)
                                                 ers)
                                               (disj mis k)]
                                              (if additional?
                                                [ers mis]
                                                [(assoc ers k {:error :additional-property}) mis])))))
                                      [{} required]
                                      data)]
      (when-not (and (empty? errors) (empty? missing))
        {:error :properties
         :data  data
         :properties (merge errors (zipmap missing (repeat {:error :missing-property})))}))))

(defn validate-number-bounds [{min           "minimum" max "maximum"
                               exclusive-min "exclusiveMinimum"
                               exclusive-max "exclusiveMaximum"} data]
  (cond
    (and min exclusive-min (<= data min))
    {:error     :out-of-bounds
     :data      data
     :minimum   min
     :exclusive true}

    (and min (< data min))
    {:error     :out-of-bounds
     :data      data
     :minimum   min
     :exclusive false}

    (and max exclusive-max (>= data max))
    {:error     :out-of-bounds
     :data      data
     :maximum   max
     :exclusive true}

    (and max (> data max))
    {:error     :out-of-bounds
     :data      data
     :maximum   max
     :exclusive false}

    :default nil))

(declare validate-enum-value)

(defn validate-array-items [options item-schema data]
  (loop [errors []
         i 0
         [item & items] data]
    (if-not item
      (if (empty? errors)
        nil
        {:error :array-items
         :data  data
         :items errors})
      (let [item-error (if (and (map? item-schema) (item-schema "enum"))
                         (validate-enum-value item-schema item)
                         (validate-by-type item-schema item options))]
        (recur (if item-error
                 (conj errors (assoc item-error
                                     :position i))
                 errors)
               (inc i)
               items)))))

(defmethod validate-by-type "integer"
  [schema data _]
  (if-not (integer? data)
    {:error :wrong-type :expected :integer :data data}
    (validate-number-bounds schema data)))

(defmethod validate-by-type "number"
  [schema data _]
  (if-not (number? data)
    {:error :wrong-type :expected :number :data data}
    (validate-number-bounds schema data)))

(defn validate-enum-value
  [{enum "enum"} data]
  (when enum
    (let [allowed-values (into #{} enum)]
      (when-not (allowed-values data)
        {:error          :invalid-enum-value
         :data           data
         :allowed-values allowed-values}))))

(defmethod validate-by-type "string"
  [schema data _]
  (if (not (string? data))
    {:error :wrong-type :expected :string :data data}
    (let [format (get schema "format")]
      (when (and format (= format "date-time") (nil? (time/from-string data)))
        {:error :wrong-format :expected :date-time :data data}))))

(defmethod validate-by-type "array"
  [schema data options]
  (if-not (sequential? data)
    {:error :wrong-type :expected :array-like :data data}
    (let [min-items (get schema "minItems")
          max-items (get schema "maxItems")
          item-schema (get schema "items")
          uniqueItems (get schema "uniqueItems")
          items (count data)]

      (cond (and min-items (> min-items items)) {:error :wrong-number-of-elements :minimum min-items :actual items}
            (and max-items (< max-items items)) {:error :wrong-number-of-elements :maximum max-items :actual items}
            (and uniqueItems (not= items (count (into #{} data)))) {:error :duplicate-items-not-allowed}
            :else (validate-array-items options item-schema data)))))

(defmethod validate-by-type "boolean"
  [_ data _]
  (when (nil? (#{true false} data))
    {:error :wrong-type :expected :boolean :data data}))

(defmethod validate-by-type :default
  [schema data _]
  ;; If no type is specified, anything goes
  nil)

(defn validate
  "Validate the given data with the given schema. Both must be in Clojure format with string keys.
  Returns nil on an error description map.

  An map of options can be given that supports the keys:
  :ref-resolver    Function for loading referenced schemas. Takes in
                   the schema URI and must return the schema parsed form.
                   Default just tries to read it as a file via slurp and parse.

  :draft3-required  when set to true, support draft3 style required (in property definition),
                    defaults to false"
  ([schema data] (validate schema data {:ref-resolver resolve-ref :root schema}))
  ([schema data options]
   (if-let [ref (get schema "$ref")]
     (let [[root referenced-schema] ((:ref-resolver options) (:root options) ref)]
       (if-not referenced-schema
         {:error       :unable-to-resolve-referenced-schema
          :schema-root (:root options)
          :schema-uri  ref}
         (validate referenced-schema data (assoc options :root root))))

     (or (validate-enum-value schema data)
         (validate-by-type schema data options)))))
