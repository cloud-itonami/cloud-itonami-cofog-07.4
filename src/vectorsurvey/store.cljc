(ns vectorsurvey.store
  "SSoT for the community vector-control / environmental-health
  monitoring Surveillance Advisor actor, behind a `Store` protocol so
  the backend is a swap, not a rewrite -- the same seam every
  `cloud-itonami` actor in this fleet uses.

  Scope note: like several siblings (e.g. `cloud-itonami-isic-3091`'s
  own `motomfg.store`), this build ships a single `MemStore` backend
  only (atom of EDN) -- the deterministic default for dev/tests/demo,
  no deps.

  This store holds ENVIRONMENTAL survey data only -- no personal or
  clinical health data of any kind. See `vectorsurvey.registry`
  ns docstring for the actor's own diagnostic-authority scope boundary.

  Five kinds of entity live here:
    - `sites`                  -- the central entity. A survey site's
                                 zone record. `:verified?` marks
                                 whether the site has actually been
                                 surveyed/confirmed (never inferred
                                 from a routine environmental-reading
                                 patch); `:registered?` marks whether
                                 it is on file in the operator's site
                                 registry.
    - `trap-units`              -- a vector-trap servicing robot's own
                                 record. `:verified?`/`:registered?`
                                 track whether it has actually been
                                 inspected/commissioned and is on file
                                 -- the same ground-truth discipline as
                                 `sites`.
    - `readings`                -- append-only measured environmental
                                 sensor readings
                                 (`vectorsurvey.telemetry/reading`),
                                 keyed by reading-id, indexed by
                                 site-id for the grounding check.
    - `survey-findings`         -- a filed vector-survey verdict
                                 (ELEVATED-RISK/NORMAL/NEEDS-MORE-DATA),
                                 keyed by survey-id, replaced on each
                                 new filing -- never merged, so a stale
                                 verdict can never linger alongside a
                                 fresh one. `vectorsurvey.governor`
                                 independently re-verifies an
                                 ELEVATED-RISK/NORMAL verdict is
                                 grounded in cited readings before it
                                 may commit.
    - `service-visits`          -- a scheduled trap service-visit DRAFT
                                 against a site
                                 (`vectorsurvey.registry`'s
                                 `register-service-visit`). Dedicated
                                 `:scheduled?` double-schedule guard
                                 (never a `:status` value -- the same
                                 discipline every prior governor's
                                 guards establish, informed by
                                 `cloud-itonami-isic-6492`'s
                                 status-lifecycle bug, ADR-2607071320).

  Plus a generic `records` map (id -> raw record) used only for
  direct, domain-agnostic `commit-record!` calls (a record with no
  `:effect` key) -- the store-level primitive every sibling actor's
  own MemStore exposes underneath its domain-specific commit dispatch.

  The ledger stays append-only: 'which site was logged, which survey
  finding was filed and on what sensor basis, which service visit was
  scheduled against a verified/registered site and trap unit, approved
  by whom, which outbreak-risk signal was escalated' is always a query
  over an immutable log -- the audit trail a public-health department
  or downstream auditor trusting this operator needs."
  (:require [vectorsurvey.registry :as registry]))

(defprotocol Store
  (site [s id])
  (all-sites [s])
  (trap-unit [s id])
  (all-trap-units [s])
  (reading [s reading-id])
  (readings-for-site [s site-id])
  (survey-finding-of [s survey-id])
  (service-visit [s id] "a scheduled service-visit record, or nil")
  (outbreak-signal-log [s] "the append-only outbreak-signal-escalation log")
  (ledger [s])
  (service-visit-history [s] "the append-only service-visit history (vectorsurvey.registry drafts)")
  (next-visit-sequence [s] "next visit-number sequence")
  (next-escalation-sequence [s] "next escalation-number sequence")
  (service-visit-already-scheduled? [s visit-id] "has this service visit already been scheduled?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (get-records [s] "the generic id -> raw-record map (domain-agnostic commit-record! path)")
  (with-sites [s sites] "replace/seed the site directory (map id->site)")
  (with-trap-units [s units] "replace/seed the trap-unit directory (map id->unit)")
  (with-readings [s readings] "replace/seed the reading directory (map id->reading)"))

;; ----------------------------- demo/sample data -----------------------------

(defn- sample-sites []
  {"site-001" {:id "site-001" :zone "riverside-park"
               :verified? true :registered? true}
   "site-002" {:id "site-002" :zone "storm-drain-district"
               :verified? true :registered? true}
   "site-003" {:id "site-003" :zone "vacant-lot-annex"
               :verified? false :registered? false}})

(defn- sample-trap-units []
  {"trap-svc-001" {:id "trap-svc-001" :kind :vector-trap-servicing-robot
                    :verified? true :registered? true}
   "trap-svc-002" {:id "trap-svc-002" :kind :vector-trap-servicing-robot
                    :verified? false :registered? false}})

(defn- sample-readings []
  {"reading-001" {:type :sensor-reading :reading-id "reading-001" :site-id "site-001"
                   :metric :trap-count :value 84 :unit "specimens"
                   :sensor-id "trap-svc-001" :timestamp "2026-07-10"}
   "reading-002" {:type :sensor-reading :reading-id "reading-002" :site-id "site-001"
                   :metric :standing-water-presence :value true :unit "boolean"
                   :sensor-id "trap-svc-001" :timestamp "2026-07-10"}
   "reading-003" {:type :sensor-reading :reading-id "reading-003" :site-id "site-002"
                   :metric :trap-count :value 3 :unit "specimens"
                   :sensor-id "trap-svc-001" :timestamp "2026-07-11"}})

;; ----------------------------- shared commit logic -----------------------------

(defn- schedule-service-visit!
  "Backend-agnostic `:service-visit/schedule` -- drafts the
  service-visit record via `vectorsurvey.registry` and returns
  {:result .. :patch ..} for the caller to persist."
  [s visit-id site-id trap-id]
  (let [seq-n (next-visit-sequence s)
        result (registry/register-service-visit visit-id site-id trap-id seq-n)]
    {:result result
     :patch {:scheduled? true
             :visit-number (get result "visit_number")}}))

(defn- file-escalation!
  "Backend-agnostic `:outbreak-signal/escalate` -- drafts the
  outbreak-signal-escalation record via `vectorsurvey.registry` and
  returns {:result .. :concern ..} for the caller to persist."
  [s concern-id value]
  (let [seq-n (next-escalation-sequence s)
        result (registry/register-escalation concern-id seq-n)]
    {:result result
     :concern (assoc value :id concern-id :escalation-number (get result "escalation_number"))}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (site [_ id] (get-in @a [:sites id]))
  (all-sites [_] (sort-by :id (vals (:sites @a))))
  (trap-unit [_ id] (get-in @a [:trap-units id]))
  (all-trap-units [_] (sort-by :id (vals (:trap-units @a))))
  (reading [_ reading-id] (get-in @a [:readings reading-id]))
  (readings-for-site [_ site-id]
    (filterv #(= site-id (:site-id %)) (vals (:readings @a))))
  (survey-finding-of [_ survey-id] (get-in @a [:survey-findings survey-id]))
  (service-visit [_ id] (get-in @a [:service-visits id]))
  (outbreak-signal-log [_] (:outbreak-signal-log @a))
  (ledger [_] (:ledger @a))
  (service-visit-history [_] (:service-visit-history @a))
  (next-visit-sequence [_] (:visit-sequence @a 0))
  (next-escalation-sequence [_] (:escalation-sequence @a 0))
  (service-visit-already-scheduled? [_ visit-id]
    (boolean (get-in @a [:service-visits visit-id :scheduled?])))
  (get-records [_] (:records @a))
  (commit-record! [s {:keys [effect path value] :as record}]
    (cond
      (= effect :site/upsert)
      (swap! a update-in [:sites (first path)] merge (assoc value :id (first path)))

      (= effect :survey/log-set)
      (swap! a assoc-in [:survey-findings (first path)] (assoc value :survey-id (first path)))

      (= effect :service-visit/schedule)
      (let [visit-id (first path)
            site-id (:site-id value)
            trap-id (:trap-id value)
            {:keys [result patch]} (schedule-service-visit! s visit-id site-id trap-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :visit-sequence (fnil inc 0))
                       (update-in [:service-visits visit-id] merge (assoc value :id visit-id) patch)
                       (update :service-visit-history registry/append result)
                       (update-in [:trap-units trap-id :last-service-visit-site]
                                  (fn [_prev] site-id)))))
        result)

      (= effect :outbreak-signal/escalate)
      (let [concern-id (first path)
            {:keys [result concern]} (file-escalation! s concern-id value)]
        (swap! a (fn [state]
                   (-> state
                       (update :escalation-sequence (fnil inc 0))
                       (update :outbreak-signal-log conj concern))))
        result)

      ;; Domain-agnostic path: a raw record with an :id and no :effect
      ;; is written verbatim into the generic `records` map -- the
      ;; store-level primitive underneath the domain-specific dispatch
      ;; above (also what `logging`-style siblings expose as their own
      ;; low-level commit path).
      (and (nil? effect) (:id record))
      (swap! a assoc-in [:records (:id record)] record)

      :else nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-sites [s sites] (when (seq sites) (swap! a assoc :sites sites)) s)
  (with-trap-units [s units] (when (seq units) (swap! a assoc :trap-units units)) s)
  (with-readings [s readings] (when (seq readings) (swap! a assoc :readings readings)) s))

(defn mem-store
  "A fresh, empty MemStore."
  []
  (->MemStore (atom {:sites {} :trap-units {} :readings {} :survey-findings {} :service-visits {}
                      :records {} :outbreak-signal-log []
                      :ledger [] :visit-sequence 0 :service-visit-history []
                      :escalation-sequence 0})))

(defn sample-data!
  "Seeds `s` (a MemStore) with a small, self-contained site +
  trap-unit + reading set -- two verified+registered sites
  (schedulable for a service visit), one UNVERIFIED/unregistered site
  (blocks any service visit scheduled against it); one
  verified+registered vector-trap-servicing-robot unit, one
  UNVERIFIED/unregistered unit; site-001 carries a FULL sensor basis
  (both required metrics, grounding an ELEVATED-RISK/NORMAL verdict),
  site-002 carries only a PARTIAL basis (one metric, insufficient to
  ground any verdict) -- so the actor + demo + tests run offline.
  Returns `s` (thread-friendly with `->`)."
  [s]
  (with-sites s (sample-sites))
  (with-trap-units s (sample-trap-units))
  (with-readings s (sample-readings))
  s)

;; ----------------------------- back-compat aliases -----------------------------
;; `get-ledger` mirrors `ledger` under the name several sibling actors'
;; own demo/test harnesses already call.

(defn get-ledger [s] (ledger s))
