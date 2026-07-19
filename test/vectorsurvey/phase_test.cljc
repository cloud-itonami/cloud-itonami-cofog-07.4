(ns vectorsurvey.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:schedule-service-visit` must NEVER be a member of any
  phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [vectorsurvey.phase :as phase]))

(deftest schedule-service-visit-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in future entries, auto-commits a real service visit"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :schedule-service-visit))
          (str "phase " n " must not auto-commit :schedule-service-visit")))))

(deftest escalate-outbreak-signal-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :escalate-outbreak-signal))
        (str "phase " n " must not auto-commit :escalate-outbreak-signal"))))

(deftest vector-survey-log-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :vector-survey-log))
        (str "phase " n " must not auto-commit :vector-survey-log"))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-risk-ops
  (testing ":log-environmental-reading carries no physical/financial risk and is never a clinical claim -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:log-environmental-reading} (:auto (get phase/phases 3))))))

(deftest schedule-service-visit-enabled-from-phase-3-only
  (is (contains? (:writes (get phase/phases 3)) :schedule-service-visit))
  (is (not (contains? (:writes (get phase/phases 2)) :schedule-service-visit)))
  (is (not (contains? (:writes (get phase/phases 1)) :schedule-service-visit))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-environmental-reading} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :schedule-service-visit} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :escalate-outbreak-signal} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :vector-survey-log} :commit)))))

(deftest gate-auto-commits-the-one-eligible-write-when-clean
  (is (= :commit (:disposition (phase/gate 3 {:op :log-environmental-reading} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-environmental-reading} :commit)))))

(deftest verdict->disposition-maps-hard-to-hold
  (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false}))))

(deftest verdict->disposition-maps-escalate
  (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true}))))

(deftest verdict->disposition-maps-commit
  (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false}))))
