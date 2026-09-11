# ADR-0001: Surveillance Advisor ⊣ Public Health Governor architecture

## Status

Accepted. `cloud-itonami-cofog-07.4` promoted from `:blueprint` to
`:implemented`, following the verified fresh-scaffold protocol
established by prior actors in this fleet.

## Context

`cloud-itonami-cofog-07.4` publishes an OSS blueprint for an
independent community vector-control and environmental-health
monitoring contractor: a sensing robot performs mosquito/vector and
environmental-health surveys under a governor-gated actor. Like every
actor in this fleet, the blueprint alone is not an implementation:
this ADR records the governed-actor architecture that promotes it to
real, tested code, following the same langgraph StateGraph +
independent Governor + Phase 0->3 rollout pattern established across
the cloud-itonami fleet.

**This is the fleet's first public-health-domain actor, and it carries
a scope boundary none of its structural templates needed: this actor
must never be mistaken for a medical or diagnostic authority.** The
structural template is `cloud-itonami-cofog-06.3`'s (water-
infrastructure leak detection) dual verified/registered-gate shape
(site + trap unit here, segment + sensor unit there) and sensor-
grounding discipline for condition verdicts
(`vectorsurvey.telemetry/grounds-verdict?`, itself modeled on
`cloud-itonami-unspsc-27`'s `formation.telemetry`) -- but NEITHER
template's domain carries a risk of being mistaken for clinical
authority, so this build adds a check with no direct precedent in
either: `diagnosis-authority-blocked-violations`, a HARD, PERMANENT,
UNCONDITIONAL block (evaluated across ALL ops, not scoped to one) on
any proposal declaring a `:diagnosis?`/`:health-determination` field.
This is structurally the SAME "evaluated unconditionally, not scoped
to a specific op" discipline `cloud-itonami-isic-3830`'s
`contamination-flag-unresolved-violations` and
`cloud-itonami-isic-3091`'s `certification-authority-blocked-
violations` each established for their own domain's permanent
authority boundary -- applied here to the boundary this domain
actually needs: never a diagnosis, never an outbreak declaration.

This vertical has NO pre-existing `kotoba-lang/vectorsurvey`-style
capability library to wrap (verified: no such repo exists). The domain
logic therefore lives as pure functions in `vectorsurvey.registry`
(and the grounding logic in `vectorsurvey.telemetry`), re-verified
independently by `vectorsurvey.governor`.

## Decision

### Decision 1: The domain-defining scope boundary -- never a medical or diagnostic authority

**This is the single most important design decision in this repo.**
`vectorsurvey.governor/diagnosis-authority-blocked-violations` blocks,
permanently and unconditionally, ANY proposal (any op, any confidence,
any phase) whose own `:value`/`:patch` declares `:diagnosis? true` or a
non-nil `:health-determination`. No human approval can override this
-- unlike every other escalation path in this actor, a diagnosis
attempt does not even reach a human via the normal high-stakes
escalation route; it is rejected outright, at the SAME governor layer
that blocks equipment/valve actuation in sibling domains. This actor:
- Surveys and reports ENVIRONMENTAL conditions only (trap catch counts, standing-water/breeding-site presence, observed vector-species category)
- Escalates elevated signals to a human public-health official
- NEVER itself diagnoses a disease, classifies a case, or declares an outbreak

### Decision 2: Self-contained domain logic (no external vector-surveillance capability library to wrap)

The site/trap-unit-verification, vector-species-validity, and
vector-count-plausibility functions live as pure functions in
`vectorsurvey.registry`; the sensor-reading shape and grounding check
live in `vectorsurvey.telemetry`. Both are re-verified independently
by `vectorsurvey.governor`.

### Decision 3: Coordination, not control -- scope boundary at the back-office

This actor is **strictly back-office coordination** of a vector-
control/environmental-health surveillance contractor's field findings
and service-visit scheduling. It does NOT:
- Actuate a treatment/pesticide dispenser directly (any live treatment dispense stays under human public-health authority) -- a PERMANENT, unconditional block
- Make a diagnosis, case classification, or outbreak declaration (Decision 1)
- Assert a site's vector risk ELEVATED-RISK/NORMAL without citing sensor readings that actually cover the required trap-count + standing-water-presence metric surface -- an ungrounded condition claim is a HARD violation

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human survey-
coordinator (or public-health official) approval.

### Decision 4: Sensor grounding for environmental-condition verdicts, exempting the honest "I don't know yet"

`:vector-survey-log` verdicts of `:elevated-risk`/`:normal` REQUIRE the
proposal's own cited `:sensor-basis` reading-ids to independently
resolve (via `vectorsurvey.telemetry/grounds-verdict?`) to readings for
the SAME site whose metrics cover BOTH `:trap-count` AND
`:standing-water-presence`. A `:needs-more-data` verdict is exempt.
These are ENVIRONMENTAL metrics only -- neither this grounding check
nor anything upstream of it ever touches a clinical/health metric.

### Decision 5: Outbreak-signal escalation -- always human sign-off, informed by (not claiming) CDC vector-surveillance framing

`:escalate-outbreak-signal` ALWAYS escalates, never auto-commits, and
is ALSO subject to Decision 1's diagnosis-authority block (an
escalation proposal that itself smuggles in a `:health-determination`
is rejected outright, never merely escalated -- see
`vectorsurvey.governor-contract-test/diagnosis-authority-is-blocked-
via-health-determination-alone`). The U.S. CDC publishes vector
surveillance and Integrated Vector Management guidance (e.g. its
published West Nile Virus surveillance-and-control guidelines,
verified real via CDC's own published materials during this ADR's
research) as a framework local programs commonly follow for field
surveillance methodology. This governor's unconditional never-auto-
resolved, never-self-diagnosing posture is the software-side analog of
routing an elevated field signal to the human public-health authority
who actually holds interpretive and diagnostic responsibility -- NOT a
claim that this software itself performs certified surveillance, holds
a public-health license, or has any diagnostic authority.

### Decision 6: Two independent verified/registered gates (site AND trap unit), not one

`:schedule-service-visit` independently verifies BOTH the referenced
**site**'s own `:verified?`/`:registered?` fields AND the referenced
**trap-servicing unit**'s own `:verified?`/`:registered?` fields before
any service visit may be scheduled -- the same dual-gate shape
`cloud-itonami-cofog-06.3`'s segment+sensor-unit gate and
`cloud-itonami-isic-3091`'s batch+equipment gate each establish for
their own domain's two entity kinds.

### Decision 7: HARD invariants (no override)

Elaborated into eleven concrete checks in `vectorsurvey.governor`:
1. **Diagnosis/health-determination authority is permanently blocked, unconditionally, across all ops (Decision 1)**
2. Site AND trap-unit records must be independently verified/registered before a service visit is scheduled against them
3. Proposals must be `:effect :propose` only (never direct dispenser control)
4. Direct treatment/pesticide-dispenser control, or treatment actuation, is permanently blocked
5. The op allowlist is closed -- `:log-environmental-reading`/`:vector-survey-log`/`:schedule-service-visit`/`:escalate-outbreak-signal` only
6. An ELEVATED-RISK/NORMAL vector-survey verdict must be grounded in cited sensor readings covering both required metrics
7. No fabricated `:species-observed` category, no physically implausible `:trap-count`
8. No double-scheduling the same service visit

## Consequences

(+) Community vector-control/environmental-health field surveillance
now has a documented, governed, auditable coordination layer that
funnels all decisions through independent validation before human
approval -- and can never be mistaken for a diagnostic system, by
construction.

(+) The "coordination, not control, and never diagnosis" boundary is
explicit in code: all `:effect :propose`, all real-world treatment
actuation requires human sign-off, and no proposal can ever declare a
diagnosis or health determination at any op or confidence.

(+) Scope is bounded and verifiable: HARD invariants (elaborated into
eleven concrete governor checks) protect against scope creep into
unauthorized treatment actuation, an ungrounded/fabricated field
finding, OR (uniquely to this domain) a self-issued diagnosis.
Outbreak-signal escalation is a circuit-breaker, not a threshold --
and even that circuit-breaker cannot carry a diagnosis through.

(-) Still a simulation/proposal layer, not a real field-operations
control system. Treatment actuation and any actual diagnostic/
outbreak-determination act remain entirely outside this software's
authority, by design.

(-) No integration with real public-health surveillance databases --
this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-cofog-07.4`: `kbb -M:test` green -- 76 tests, 209
  assertions, 0 failures, 0 errors (verified from a fresh checkout),
  including two dedicated tests
  (`diagnosis-authority-is-held-and-permanently-blocked`,
  `diagnosis-authority-is-blocked-via-health-determination-alone`)
  proving the domain-defining boundary holds even when the offending
  field is smuggled into an otherwise-routine or otherwise-always-
  escalating op. Demo narrative (`kbb -M:dev:run`) exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, diagnosis-authority-
  blocked, survey-verdict-ungrounded, site-not-verified, trap-unit-
  not-verified, treatment-actuate-blocked, already-scheduled, invalid-
  vector-species, invalid-vector-count).
- `kbb -M:lint` (clj-kondo): 0 errors, 0 warnings.
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps`, so a bare `kbb -M:test` resolves offline
  inside the monorepo checkout.
