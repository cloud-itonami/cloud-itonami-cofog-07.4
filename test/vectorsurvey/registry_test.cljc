(ns vectorsurvey.registry-test
  (:require [clojure.test :refer [deftest is]]
            [vectorsurvey.registry :as r]))

;; ----------------------------- site-verified? / site-registered? / site-ready? -----------------------------

(deftest site-is-verified-when-flagged
  (is (true? (r/site-verified? {:id "s1" :verified? true}))))

(deftest site-is-not-verified-when-false-or-missing
  (is (false? (r/site-verified? {:id "s1" :verified? false})))
  (is (false? (r/site-verified? {:id "s1"}))))

(deftest site-is-registered-when-flagged
  (is (true? (r/site-registered? {:registered? true}))))

(deftest site-is-not-registered-when-false-or-missing
  (is (false? (r/site-registered? {:registered? false})))
  (is (false? (r/site-registered? {}))))

(deftest site-ready-requires-both
  (is (true? (r/site-ready? {:verified? true :registered? true})))
  (is (false? (r/site-ready? {:verified? true :registered? false})))
  (is (false? (r/site-ready? {:verified? false :registered? true})))
  (is (false? (r/site-ready? {}))))

;; ----------------------------- trap-unit-verified? / trap-unit-registered? / trap-unit-ready? -----------------------------

(deftest trap-unit-is-verified-when-flagged
  (is (true? (r/trap-unit-verified? {:id "u1" :verified? true}))))

(deftest trap-unit-is-not-verified-when-false-or-missing
  (is (false? (r/trap-unit-verified? {:id "u1" :verified? false})))
  (is (false? (r/trap-unit-verified? {:id "u1"}))))

(deftest trap-unit-is-registered-when-flagged
  (is (true? (r/trap-unit-registered? {:registered? true}))))

(deftest trap-unit-is-not-registered-when-false-or-missing
  (is (false? (r/trap-unit-registered? {:registered? false})))
  (is (false? (r/trap-unit-registered? {}))))

(deftest trap-unit-ready-requires-both
  (is (true? (r/trap-unit-ready? {:verified? true :registered? true})))
  (is (false? (r/trap-unit-ready? {:verified? true :registered? false})))
  (is (false? (r/trap-unit-ready? {:verified? false :registered? true})))
  (is (false? (r/trap-unit-ready? {}))))

;; ----------------------------- vector-species-valid? -----------------------------

(deftest known-vector-species-are-valid
  (doseq [sp [:mosquito-culex :mosquito-aedes :mosquito-anopheles
              :rodent-sign :tick :fly :other-arthropod]]
    (is (r/vector-species-valid? sp))))

(deftest fabricated-vector-species-is-invalid
  (is (not (r/vector-species-valid? :dragon)))
  (is (not (r/vector-species-valid? nil))))

;; ----------------------------- vector-count-valid? -----------------------------

(deftest typical-vector-count-is-valid
  (is (r/vector-count-valid? 0))
  (is (r/vector-count-valid? 84))
  (is (r/vector-count-valid? 50000)))

(deftest negative-vector-count-is-invalid
  (is (not (r/vector-count-valid? -1))))

(deftest excessive-vector-count-is-invalid
  (is (not (r/vector-count-valid? 999999)))
  (is (not (r/vector-count-valid? 50001))))

(deftest non-numeric-or-missing-vector-count-is-invalid
  (is (not (r/vector-count-valid? nil)))
  (is (not (r/vector-count-valid? "84"))))

;; ----------------------------- register-service-visit -----------------------------

(deftest service-visit-is-a-draft-not-a-real-actuation
  (let [result (r/register-service-visit "visit-1" "site-001" "trap-svc-001" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest service-visit-assigns-visit-number
  (let [result (r/register-service-visit "visit-1" "site-001" "trap-svc-001" 7)]
    (is (= (get result "visit_number") "SVC-000007"))
    (is (= (get-in result ["record" "visit_id"]) "visit-1"))
    (is (= (get-in result ["record" "site_id"]) "site-001"))
    (is (= (get-in result ["record" "trap_id"]) "trap-svc-001"))
    (is (= (get-in result ["record" "kind"]) "service-visit-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest service-visit-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-service-visit "" "site-001" "trap-svc-001" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-service-visit "visit-1" "" "trap-svc-001" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-service-visit "visit-1" "site-001" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-service-visit "visit-1" "site-001" "trap-svc-001" -1))))

;; ----------------------------- register-escalation -----------------------------

(deftest escalation-is-a-draft-not-a-real-determination
  (let [result (r/register-escalation "concern-1" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest escalation-assigns-escalation-number
  (let [result (r/register-escalation "concern-1" 7)]
    (is (= (get result "escalation_number") "ESC-000007"))
    (is (= (get-in result ["record" "concern_id"]) "concern-1"))
    (is (= (get-in result ["record" "kind"]) "outbreak-signal-escalation-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest escalation-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-escalation "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-escalation "concern-1" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-service-visit "visit-1" "site-001" "trap-svc-001" 0)
        hist (r/append [] c1)
        c2 (r/register-service-visit "visit-2" "site-002" "trap-svc-001" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "SVC-000000" (get-in hist2 [0 "record_id"])))
    (is (= "SVC-000001" (get-in hist2 [1 "record_id"])))))
