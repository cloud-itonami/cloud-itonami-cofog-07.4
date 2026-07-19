(ns vectorsurvey.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  scope boundary ('does NOT actuate a treatment/pesticide dispenser
  directly... does NOT authorize or execute a real treatment... NEVER
  a medical or diagnostic authority... never accepts a self-attested
  environmental-condition finding') implemented faithfully. The
  MOST IMPORTANT test in this suite is `diagnosis-authority-is-held-
  and-permanently-blocked` -- this actor must never be able to reach a
  commit path for any proposal that declares a diagnosis or health
  determination, at ANY op, at ANY confidence, at ANY phase.

  The overall invariant under test:

    Surveillance Advisor never schedules a service visit, files an
    ungrounded survey verdict, self-issues a diagnosis, or escalates an
    outbreak-risk signal the Public Health Governor would reject;
    `:vector-survey-log`/`:schedule-service-visit`/`:escalate-outbreak-
    signal` NEVER auto-commit at any phase;
    `:log-environmental-reading` (no physical/financial risk, and never
    a clinical claim) MAY auto-commit when clean; and every decision
    (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [vectorsurvey.store :as store]
            [vectorsurvey.operation :as op]))

(defn- fresh []
  (let [db (-> (store/mem-store) (store/sample-data!))]
    [db (op/build db)]))

(def coordinator {:actor-id "coord-1" :actor-role :survey-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest clean-log-environmental-reading-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-environmental-reading :effect :propose :subject "site-001"
                   :patch {:standing-water-detected? true}} coordinator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (true? (:standing-water-detected? (store/site db "site-001"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest diagnosis-authority-is-held-and-permanently-blocked
  (testing "THE domain-defining invariant: a proposal declaring :diagnosis? true -> HOLD, PERMANENT, never reaches request-approval, regardless of op or confidence"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :log-environmental-reading :effect :propose :subject "site-001"
                     :patch {:diagnosis? true :health-determination :dengue-outbreak-confirmed}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:diagnosis-authority-blocked} (-> (store/ledger db) last :basis)))
      (is (not (true? (:diagnosis? (store/site db "site-001")))) "fabricated self-diagnosis never lands in the SSoT")
      (is (nil? (:health-determination (store/site db "site-001")))))))

(deftest diagnosis-authority-is-blocked-via-health-determination-alone
  (testing "a :health-determination field alone (without :diagnosis? true) is also a HARD, PERMANENT block"
    (let [[db actor] (fresh)
          res (exec-op actor "t2b"
                    {:op :escalate-outbreak-signal :effect :propose :subject "concern-x"
                     :value {:site-id "site-001" :severity :high :description "y"
                             :health-determination :outbreak-confirmed}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt, even though escalate-outbreak-signal is normally high-stakes-escalate")
      (is (not= :interrupted (:status res)))
      (is (some #{:diagnosis-authority-blocked} (-> (store/ledger db) last :basis))))))

(deftest grounded-elevated-risk-survey-escalates-then-commits
  (testing "vector-survey-log is never in any phase's :auto set -- always human approval, even when grounded and clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :vector-survey-log :effect :propose :subject "survey-1"
                     :value {:site-id "site-001" :verdict :elevated-risk
                             :species-observed :mosquito-culex
                             :sensor-basis ["reading-001" "reading-002"]}}
                    coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t3")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :elevated-risk (:verdict (store/survey-finding-of db "survey-1"))))))))

(deftest schedule-service-visit-always-needs-approval
  (testing "service-visit scheduling is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t4"
                    {:op :schedule-service-visit :effect :propose :subject "visit-1"
                     :value {:site-id "site-001" :trap-id "trap-svc-001"
                             :scheduled-date "2026-08-01" :actuate-treatment? false}}
                    coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t4")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:scheduled? (store/service-visit db "visit-1"))))
        (is (= 1 (count (store/service-visit-history db))))))))

(deftest effect-not-propose-is-held
  (testing "a request whose own :effect is not :propose -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :log-environmental-reading :effect :direct-write :subject "site-001"
                     :patch {:standing-water-detected? true}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:not-propose-effect} (-> (store/ledger db) first :basis))))))

(deftest unknown-op-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t6" {:op :dispense-pesticide :effect :propose :subject "x"} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:unknown-op} (-> (store/ledger db) first :basis)))))

(deftest ungrounded-elevated-risk-verdict-is-held-and-unoverridable
  (testing "an ELEVATED-RISK verdict citing only ONE of the two required sensor metrics -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t7"
                    {:op :vector-survey-log :effect :propose :subject "survey-2"
                     :value {:site-id "site-002" :verdict :elevated-risk
                             :species-observed :mosquito-aedes
                             :sensor-basis ["reading-003"]}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:survey-verdict-ungrounded} (-> (store/ledger db) last :basis)))
      (is (nil? (store/survey-finding-of db "survey-2")) "an ungrounded verdict never lands in the SSoT"))))

(deftest needs-more-data-verdict-is-exempt-from-grounding
  (testing "a :needs-more-data verdict needs no sensor basis -- the honest 'I don't know yet' outcome, still always escalates"
    (let [[db actor] (fresh)
          res (exec-op actor "t8"
                    {:op :vector-survey-log :effect :propose :subject "survey-3"
                     :value {:site-id "site-003" :verdict :needs-more-data
                             :sensor-basis []}}
                    coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t8")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :needs-more-data (:verdict (store/survey-finding-of db "survey-3"))))))))

(deftest site-not-verified-is-held-and-unoverridable
  (testing "scheduling a service visit against an unverified/unregistered site -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t9"
                    {:op :schedule-service-visit :effect :propose :subject "visit-2"
                     :value {:site-id "site-003" :trap-id "trap-svc-001"
                             :scheduled-date "2026-08-01" :actuate-treatment? false}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:site-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/service-visit-history db))))))

(deftest trap-unit-not-verified-is-held-and-unoverridable
  (testing "scheduling a service visit with an unverified/unregistered trap unit -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t10"
                    {:op :schedule-service-visit :effect :propose :subject "visit-3"
                     :value {:site-id "site-002" :trap-id "trap-svc-002"
                             :scheduled-date "2026-08-01" :actuate-treatment? false}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:trap-unit-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/service-visit-history db))))))

(deftest treatment-actuate-is-held-and-permanently-blocked
  (testing "a proposal that sets :actuate-treatment? true -> HOLD, PERMANENT, never reaches request-approval even though the site and trap unit are verified and registered"
    (let [[db actor] (fresh)
          res (exec-op actor "t11"
                    {:op :schedule-service-visit :effect :propose :subject "visit-4"
                     :value {:site-id "site-001" :trap-id "trap-svc-001"
                             :scheduled-date "2026-09-01" :actuate-treatment? true}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:treatment-actuate-blocked} (-> (store/ledger db) last :basis)))
      (is (empty? (store/service-visit-history db))))))

(deftest schedule-service-visit-double-schedule-is-held
  (testing "scheduling the SAME service visit twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (exec-op actor "t12a" {:op :schedule-service-visit :effect :propose :subject "visit-1"
                                   :value {:site-id "site-001" :trap-id "trap-svc-001"
                                           :scheduled-date "2026-08-01" :actuate-treatment? false}} coordinator)
          _ (approve! actor "t12a")
          res (exec-op actor "t12" {:op :schedule-service-visit :effect :propose :subject "visit-1"
                                    :value {:site-id "site-001" :trap-id "trap-svc-001"
                                            :scheduled-date "2026-08-01" :actuate-treatment? false}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-scheduled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/service-visit-history db))) "still only the one earlier schedule"))))

(deftest invalid-vector-species-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t13" {:op :vector-survey-log :effect :propose :subject "survey-4"
                                  :value {:site-id "site-001" :verdict :elevated-risk
                                          :species-observed :dragon
                                          :sensor-basis ["reading-001" "reading-002"]}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-vector-species} (-> (store/ledger db) last :basis)))
    (is (nil? (store/survey-finding-of db "survey-4")) "fabricated species category never lands in the SSoT")))

(deftest invalid-vector-count-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t14" {:op :log-environmental-reading :effect :propose :subject "site-001"
                                  :patch {:trap-count 999999}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-vector-count} (-> (store/ledger db) last :basis)))
    (is (not= 999999 (:trap-count (store/site db "site-001"))) "fabricated trap-count never lands in the SSoT")))

(deftest outbreak-signal-always-escalates-even-high-confidence
  (testing "escalate-outbreak-signal always escalates -- never auto-committed, regardless of confidence"
    (let [[db actor] (fresh)
          res (exec-op actor "t15" {:op :escalate-outbreak-signal :effect :propose :subject "concern-1"
                                    :value {:site-id "site-001" :severity :high
                                            :description "trap count spike, standing water confirmed"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t15")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/outbreak-signal-log db))))))))

(deftest outbreak-signal-approval-rejected-leaves-no-record-only-a-hold-fact
  (let [[db actor] (fresh)
        _ (exec-op actor "t16" {:op :escalate-outbreak-signal :effect :propose :subject "concern-2"
                                :value {:site-id "site-001" :severity :low :description "y"}}
                   coordinator)
        r (reject! actor "t16")]
    (is (= :hold (get-in r [:state :disposition])))
    (is (= 0 (count (store/outbreak-signal-log db))) "rejected approval never reaches the commit node")
    (is (= 1 (count (store/ledger db))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N settled operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-environmental-reading :effect :propose :subject "site-001"
                          :patch {:standing-water-detected? true}} coordinator)
      (exec-op actor "b" {:op :log-environmental-reading :effect :propose :subject "site-001"
                          :patch {:trap-count 999999}} coordinator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
