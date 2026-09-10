(ns vectorsurvey.store-contract-test
  "The Store contract as executable tests. Single MemStore backend --
  see `vectorsurvey.store` ns docstring for why a second (Datomic-
  backed) backend is out of scope for this build."
  (:require [clojure.test :refer [deftest is testing]]
            [vectorsurvey.store :as store]))

(defn- seeded [] (-> (store/mem-store) (store/sample-data!)))

(deftest sample-data-read-basics
  (let [s (seeded)]
    (is (true? (:verified? (store/site s "site-001"))))
    (is (true? (:registered? (store/site s "site-001"))))
    (is (true? (:verified? (store/site s "site-002"))))
    (is (true? (:registered? (store/site s "site-002"))))
    (is (false? (:verified? (store/site s "site-003"))))
    (is (false? (:registered? (store/site s "site-003"))))
    (is (= ["site-001" "site-002" "site-003"] (mapv :id (store/all-sites s))))
    (is (true? (:verified? (store/trap-unit s "trap-svc-001"))))
    (is (true? (:registered? (store/trap-unit s "trap-svc-001"))))
    (is (false? (:verified? (store/trap-unit s "trap-svc-002"))))
    (is (false? (:registered? (store/trap-unit s "trap-svc-002"))))
    (is (= ["trap-svc-001" "trap-svc-002"] (mapv :id (store/all-trap-units s))))
    (is (= 2 (count (store/readings-for-site s "site-001"))))
    (is (= 1 (count (store/readings-for-site s "site-002"))))
    (is (= 0 (count (store/readings-for-site s "site-003"))))
    (is (= [] (store/ledger s)))
    (is (= [] (store/service-visit-history s)))
    (is (= [] (store/outbreak-signal-log s)))
    (is (zero? (store/next-visit-sequence s)))
    (is (zero? (store/next-escalation-sequence s)))
    (is (false? (store/service-visit-already-scheduled? s "visit-1")))
    (is (nil? (store/survey-finding-of s "survey-1")))))

(deftest fresh-store-has-no-sites-or-trap-units
  (let [s (store/mem-store)]
    (is (= [] (store/all-sites s)))
    (is (nil? (store/site s "site-001")))
    (is (= [] (store/all-trap-units s)))
    (is (nil? (store/trap-unit s "trap-svc-001")))
    (is (= [] (store/readings-for-site s "site-001")))))

(deftest site-upsert-merges-preserving-untouched-fields
  (let [s (seeded)]
    (store/commit-record! s {:effect :site/upsert :path ["site-001"]
                             :value {:standing-water-detected? true}})
    (is (true? (:standing-water-detected? (store/site s "site-001"))))
    (is (true? (:verified? (store/site s "site-001"))) "unrelated field preserved")
    (is (true? (:registered? (store/site s "site-001"))) "unrelated field preserved")))

(deftest survey-log-set-replaces-not-merges
  (let [s (seeded)]
    (store/commit-record! s {:effect :survey/log-set :path ["survey-1"]
                             :value {:site-id "site-001" :verdict :elevated-risk
                                     :sensor-basis ["reading-001" "reading-002"]}})
    (is (= :elevated-risk (:verdict (store/survey-finding-of s "survey-1"))))
    (store/commit-record! s {:effect :survey/log-set :path ["survey-1"]
                             :value {:site-id "site-001" :verdict :normal
                                     :sensor-basis ["reading-001" "reading-002"]}})
    (is (= :normal (:verdict (store/survey-finding-of s "survey-1")))
        "the new filing fully replaces the stale one")))

(deftest service-visit-schedule-commits-and-advances-sequence
  (testing "commit-record! (like every sibling actor's own MemStore) returns the store `s`, not the domain result -- inspect the store directly, matching the discipline the actor's own :commit node relies on"
    (let [s (seeded)]
      (store/commit-record! s {:effect :service-visit/schedule :path ["visit-1"]
                               :value {:site-id "site-001" :trap-id "trap-svc-001"
                                       :scheduled-date "2026-08-01"}})
      (is (= "SVC-000000" (get (first (store/service-visit-history s)) "record_id")))
      (is (= "service-visit-draft" (get (first (store/service-visit-history s)) "kind")))
      (is (true? (:scheduled? (store/service-visit s "visit-1"))))
      (is (= "site-001" (:site-id (store/service-visit s "visit-1"))))
      (is (= 1 (count (store/service-visit-history s))))
      (is (= 1 (store/next-visit-sequence s)))
      (is (true? (store/service-visit-already-scheduled? s "visit-1"))))))

(deftest outbreak-signal-flag-appends
  (let [s (seeded)]
    (store/commit-record! s {:effect :outbreak-signal/escalate :path ["concern-1"]
                             :value {:site-id "site-001" :severity :high}})
    (is (= 1 (count (store/outbreak-signal-log s))))
    (is (= :high (:severity (first (store/outbreak-signal-log s)))))
    (store/commit-record! s {:effect :outbreak-signal/escalate :path ["concern-2"]
                             :value {:site-id "site-002" :severity :moderate}})
    (is (= 2 (count (store/outbreak-signal-log s))) "append-only")))

(deftest ledger-is-append-only-and-order-preserving
  (let [s (store/mem-store)]
    (store/append-ledger! s {:op :a :disposition :commit})
    (store/append-ledger! s {:op :b :disposition :hold})
    (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))

(deftest generic-commit-record-path-writes-a-raw-record-by-id
  (testing "a record with no :effect key is written verbatim into the generic records map -- the store-level primitive underneath the domain-specific dispatch"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))

(deftest get-ledger-alias-matches-ledger
  (let [s (store/mem-store)]
    (store/append-ledger! s {:t :x})
    (is (= (store/ledger s) (store/get-ledger s)))))
