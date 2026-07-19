(ns vectorsurvey.registry
  "Pure-function domain logic for the community vector-control /
  environmental-health monitoring Surveillance Advisor actor --
  site/trap-unit verification, vector-species-category validation,
  vector-count plausibility validation, and draft
  service-visit/escalation record construction.

  **THIS ACTOR IS NOT A MEDICAL OR DIAGNOSTIC AUTHORITY.** It surveys
  and reports ENVIRONMENTAL conditions only (trap catch counts,
  standing-water/breeding-site presence, observed vector-species
  category) -- it never diagnoses a disease, never asserts a case
  count, and never issues any clinical/medical determination about any
  person. See `vectorsurvey.governor`'s `diagnosis-authority-blocked-
  violations` (a PERMANENT, unconditional block on ANY proposal, any
  op, that declares a `:diagnosis?`/`:health-determination` field) and
  README `What this actor does NOT do`.

  Per docs/adr/0001-architecture.md Decision 1: this vertical has no
  pre-existing `kotoba-lang/vectorsurvey`-style capability library to
  wrap (verified: no such repo exists). The domain logic therefore
  lives here as pure functions, re-verified INDEPENDENTLY by
  `vectorsurvey.governor` -- the same 'ground truth, not self-report'
  discipline every sibling actor's own registry establishes: never
  trust a proposal's own self-reported species/count claim when the
  inputs needed to independently validate it are already on record, or
  are simple physical-plausibility bounds.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real public-health surveillance system. It builds the
  DRAFT record a survey coordinator would keep (a scheduled trap
  service visit, a filed outbreak-risk-signal escalation), not the act
  of actuating a treatment/pesticide dispenser directly, and never a
  public-health authority's own outbreak determination or case
  classification (this actor NEVER does either -- see README `What
  this actor does NOT do`).

  SCOPE: COFOG 07.4 covers community vector-control and environmental-
  health field surveillance -- trap servicing, breeding-site imaging,
  and environmental-reading logging for a contracted surveillance
  operator. This actor coordinates the back-office record-keeping
  around that field work (environmental-reading logging, vector-survey
  finding, service-visit scheduling, outbreak-signal escalation) -- it
  never actuates a treatment dispenser directly, and it never stands in
  for a public-health authority's own diagnostic or outbreak-
  determination role.

  Independent surveillance context (informational, not a claim of
  compliance or clinical authority): the U.S. CDC publishes vector
  (e.g. mosquito) surveillance and Integrated Vector Management (IVM)
  guidance -- e.g. its 'Guidelines for West Nile Virus Surveillance and
  Control' (verified real via CDC's own published guidance during this
  build's research) -- as a framework local mosquito-control programs
  commonly follow for field surveillance methodology. The measured
  trap-count/breeding-site data this actor logs is the kind of
  environmental input such surveillance programs collect; this
  software does not itself perform, certify, or replace a public-health
  authority's own surveillance program, IVM plan, or disease
  determination.")

;; ----------------------------- constants -----------------------------

(def valid-vector-species
  "The closed set of vector-species-category values a vector-survey
  finding may declare -- GENERIC field-observation categories (never a
  disease-diagnosis label; this actor observes and categorizes trapped
  specimens, it never diagnoses an illness in a person or asserts a
  specific pathogen). Anything else is a fabricated/unrecognized
  category -- the governor HARD-holds rather than let an invented
  category pass through."
  #{:mosquito-culex :mosquito-aedes :mosquito-anopheles
    :rodent-sign :tick :fly :other-arthropod})

(def vector-count-min
  "Physical floor for a trap's own reported catch count (an empty trap
  legitimately reports 0)."
  0)

(def vector-count-max
  "Physical ceiling for a single trap's own reported catch count --
  generous enough to cover a heavily-populated trap night, but bounded
  so an implausible/miscounted reading is rejected rather than silently
  accepted into a survey record."
  50000)

;; ----------------------------- site checks -----------------------------

(defn site-verified?
  "Ground-truth check: has `site`'s own record been marked verified
  (i.e. it has actually been surveyed/confirmed by the operator or
  public-health department, not merely referenced from an unverified
  service-visit request)? A pure predicate over the site's own
  permanent field -- no proposal inspection needed."
  [site]
  (true? (:verified? site)))

(defn site-registered?
  "Ground-truth check: does `site`'s own record carry a `:registered?`
  true flag (i.e. it is on file in the operator's site registry)?
  Scheduling a service visit against a site that is not on file and
  registered is the exact scope violation this actor's HARD invariant
  ('site/trap-unit record must be independently verified/registered
  before any action') exists to block."
  [site]
  (true? (:registered? site)))

(defn site-ready?
  "Combined ground-truth gate: the site must be both `verified?` AND
  `registered?` before ANY service visit may be scheduled against it.
  Two independent facts on the site's own permanent record, neither
  inferred from the advisor's own rationale."
  [site]
  (and (site-verified? site) (site-registered? site)))

;; ----------------------------- trap-unit checks -----------------------------

(defn trap-unit-verified?
  "Ground-truth check: has `unit`'s own record been marked verified
  (i.e. the vector-trap servicing robot has actually been inspected/
  commissioned and registered in the SSoT)?"
  [unit]
  (true? (:verified? unit)))

(defn trap-unit-registered?
  "Ground-truth check: does `unit`'s own record carry a `:registered?`
  true flag (i.e. it is on file in the operator's trap-fleet
  registry)?"
  [unit]
  (true? (:registered? unit)))

(defn trap-unit-ready?
  "Combined ground-truth gate: the trap-servicing unit must be both
  `verified?` AND `registered?` before ANY service visit may be
  scheduled against it."
  [unit]
  (and (trap-unit-verified? unit) (trap-unit-registered? unit)))

;; ----------------------------- record-field validation -----------------------------

(defn vector-species-valid?
  "Is `species` one of the closed, known vector-species-category
  values this actor may observe/report? nil/blank is treated as
  invalid (a survey finding must declare a real observed category, not
  omit it silently). This is a FIELD-OBSERVATION category, never a
  disease diagnosis."
  [species]
  (contains? valid-vector-species species))

(defn vector-count-valid?
  "Is `count*` a physically plausible reported trap catch count?
  Rejects nil, non-numbers, negative values, and values beyond
  `vector-count-max` -- a fabricated or miscounted reading, never let
  through as a real environmental-reading fact."
  [count*]
  (and (number? count*)
       (>= count* vector-count-min)
       (<= count* vector-count-max)))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human survey coordinator's/public-health official's act, not
  this actor's. And NEVER a disease determination or outbreak
  declaration -- this actor is never the public-health authority's own
  diagnostic or outbreak-declaration authority (see README `What this
  actor does NOT do`)."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-service-visit
  "Validate + construct the SERVICE-VISIT DRAFT -- a proposed trap
  servicing / breeding-site treatment visit against a verified,
  registered site and a verified, registered trap-servicing unit. Pure
  function -- does not actuate any treatment/pesticide dispenser or
  execute any treatment; it builds the RECORD a survey coordinator
  would keep. `vectorsurvey.governor` independently re-verifies the
  site's and trap unit's own verified/registered ground truth, and
  permanently blocks any attempt to directly actuate a treatment
  dispenser (see README `Actuation`), before this is ever allowed to
  commit."
  [visit-id site-id trap-id sequence]
  (when-not (and visit-id (not= visit-id ""))
    (throw (ex-info "service-visit: visit_id required" {})))
  (when-not (and site-id (not= site-id ""))
    (throw (ex-info "service-visit: site_id required" {})))
  (when-not (and trap-id (not= trap-id ""))
    (throw (ex-info "service-visit: trap_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "service-visit: sequence must be >= 0" {})))
  (let [visit-number (str "SVC-" (zero-pad sequence 6))
        record {"record_id" visit-number
                "kind" "service-visit-draft"
                "visit_id" visit-id
                "site_id" site-id
                "trap_id" trap-id
                "immutable" true}]
    {"record" record "visit_number" visit-number
     "certificate" (unsigned-certificate "ServiceVisit" visit-number visit-number)}))

(defn register-escalation
  "Validate + construct the OUTBREAK-SIGNAL-ESCALATION DRAFT -- a
  filed elevated-vector-risk concern, routed to a human public-health
  official for review. Pure function -- does not itself declare an
  outbreak, diagnose a disease, or classify a case; it builds the
  RECORD a survey coordinator would keep pending human public-health
  review. CDC vector-surveillance guidance (e.g. its published West
  Nile Virus surveillance-and-control guidelines, verified real during
  this build's research) frames elevated vector signals as findings
  that route to a public-health authority for interpretation and
  response -- this record's own never-auto-resolved, never-self-
  diagnosing posture is the software-side analog of that same routing
  discipline, not a claim that this software itself performs
  surveillance certified to any program, or that it has diagnostic or
  outbreak-declaration authority."
  [concern-id sequence]
  (when-not (and concern-id (not= concern-id ""))
    (throw (ex-info "escalation: concern_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "escalation: sequence must be >= 0" {})))
  (let [escalation-number (str "ESC-" (zero-pad sequence 6))
        record {"record_id" escalation-number
                "kind" "outbreak-signal-escalation-draft"
                "concern_id" concern-id
                "immutable" true}]
    {"record" record "escalation_number" escalation-number
     "certificate" (unsigned-certificate "OutbreakSignalEscalation" escalation-number escalation-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
