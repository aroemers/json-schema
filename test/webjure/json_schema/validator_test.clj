(ns webjure.json-schema.validator-test
  (:require [clojure.test :refer [deftest testing is]]
            [webjure.json-schema.validator :refer [validate]]
            [cheshire.core :as cheshire]))


(defn p [resource-path]
  (->> resource-path
       (str "test/resources/")
       slurp cheshire/parse-string))

(deftest validate-address-and-phone
  (testing "jsonschema.net example schema"
    (let [s (p "address-and-phone.schema.json")]
      (testing "valid json returns nil errors"
        (is (nil? (validate s (p "address-and-phone-valid.json")))))

      (testing "missing property error is reported"
        (let [e (validate s (p "address-and-phone-city-and-code-missing.json"))]
          (is (= :properties (:error e)))
          ;; "city" is reported as missing because it is required
          (is (= :missing-property (get-in e [:properties "address" :properties "city" :error])))

          ;; no errors in "phoneNumber" because missing "code" in first item is not required
          (is (nil? (get-in e [:properties "phoneNumber"])))))

      (testing "additional properties are reported"
        (is (= {:error :additional-properties
                :property-names #{"youDidntExpectMe" "orMe"}}
               (validate s (p "address-and-phone-additional-properties.json"))))))))

(deftest validate-referenced-schema
  (testing "person schema that links to address and phone schema"
    (let [s (p "person.schema.json")]
      (testing "valid json returns nil errors"
        (is (nil? (validate s (p "person-valid.json")))))
      (testing "linked schema errors are reported"
        (is (= :missing-property
               (get-in (validate s (p "person-invalid.json"))
                       [:properties "contact" :properties "phoneNumber" :error]))))
      )))



  