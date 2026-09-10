(ns vectorsurvey.telemetry-test
  (:require [clojure.test :refer [deftest is]]
            [vectorsurvey.telemetry :as t]))

(defn- mk [id site metric value]
  (t/reading {:reading-id id :site-id site :metric metric :value value :sensor-id "trap-svc-001"}))

;; ----------------------------- reading -----------------------------

(deftest reading-constructs-a-valid-shape
  (let [r (mk "r1" "site-001" :trap-count 42)]
    (is (= :sensor-reading (:type r)))
    (is (= "r1" (:reading-id r)))
    (is (= "site-001" (:site-id r)))
    (is (= :trap-count (:metric r)))
    (is (= 42 (:value r)))))

(deftest reading-requires-reading-id
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (t/reading {:site-id "s1" :metric :trap-count :value 1 :sensor-id "a1"}))))

(deftest reading-requires-site-id
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (t/reading {:reading-id "r1" :metric :trap-count :value 1 :sensor-id "a1"}))))

(deftest reading-requires-keyword-metric
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (t/reading {:reading-id "r1" :site-id "s1" :metric "trap-count" :value 1 :sensor-id "a1"}))))

(deftest reading-requires-non-nil-value
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (t/reading {:reading-id "r1" :site-id "s1" :metric :trap-count :value nil :sensor-id "a1"}))))

(deftest reading-requires-sensor-id
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (t/reading {:reading-id "r1" :site-id "s1" :metric :trap-count :value 1}))))

;; ----------------------------- grounds-verdict? -----------------------------

(deftest full-coverage-grounds-a-verdict
  (let [readings [(mk "r1" "site-001" :trap-count 42)
                  (mk "r2" "site-001" :standing-water-presence true)]]
    (is (true? (t/grounds-verdict? "site-001" ["r1" "r2"] readings)))))

(deftest partial-coverage-does-not-ground
  (let [readings [(mk "r1" "site-001" :trap-count 42)]]
    (is (false? (t/grounds-verdict? "site-001" ["r1"] readings)))))

(deftest no-citations-does-not-ground
  (let [readings [(mk "r1" "site-001" :trap-count 42)
                  (mk "r2" "site-001" :standing-water-presence true)]]
    (is (false? (t/grounds-verdict? "site-001" [] readings)))
    (is (false? (t/grounds-verdict? "site-001" nil readings)))))

(deftest citing-another-sites-reading-does-not-ground
  (let [readings [(mk "r1" "site-001" :trap-count 42)
                  (mk "r2" "site-002" :standing-water-presence true)]]
    (is (false? (t/grounds-verdict? "site-001" ["r1" "r2"] readings))
        "r2 belongs to site-002, not site-001 -- citing it grounds nothing")))

(deftest citing-a-nonexistent-reading-does-not-ground
  (let [readings [(mk "r1" "site-001" :trap-count 42)
                  (mk "r2" "site-001" :standing-water-presence true)]]
    (is (false? (t/grounds-verdict? "site-001" ["r1" "r2" "r-does-not-exist"] readings))
        "a cited id that resolves to nothing shrinks the distinct-count match")))

(deftest citing-the-same-reading-twice-does-not-widen-coverage
  (let [readings [(mk "r1" "site-001" :trap-count 42)]]
    (is (false? (t/grounds-verdict? "site-001" ["r1" "r1"] readings))
        "duplicate citation of the same reading cannot substitute for the missing metric")))

(deftest readings-by-id-indexes-last-wins
  (let [r1 (mk "r1" "site-001" :trap-count 10)
        r1b (mk "r1" "site-001" :trap-count 90)
        idx (t/readings-by-id [r1 r1b])]
    (is (= 90 (:value (get idx "r1"))))))
