# cloud-itonami-cofog-07.4

Open COFOG Blueprint (implemented actor) for **COFOG 07.4**: Public
health services.

This repository publishes a forkable OSS business for an independent
community vector-control and environmental-health monitoring contractor: a
sensing robot performs mosquito/vector and environmental-health surveys
under a governor-gated actor, so a municipal public-health department (or
its contracted operator) keeps auditable surveillance records instead of
renting a closed field-service SaaS. Complements
[`cloud-itonami-8691`](https://github.com/cloud-itonami/cloud-itonami-8691)
(Health Access Navigation) at the environmental-surveillance layer.

**Maturity: `:implemented`** — Surveillance Advisor ⊣ Public Health
Governor as a langgraph StateGraph (`intake → advise → govern → decide
→ commit/hold`, human-approval interrupt), modeled on
`cloud-itonami-cofog-06.3`'s (water leak detection) dual verified/
registered-gate shape and sensor-grounding discipline. 76 tests / 209
assertions green, `clj-kondo` 0 errors / 0 warnings. See
[`cloud-itonami-isco-1324`](https://github.com/cloud-itonami/cloud-itonami-isco-1324)
for this fleet's minimal implemented reference (`actor`/`advisor`/
`governor`/`store`).

## NOT a medical or diagnostic authority

**This is the single most important fact about this software.** This
actor surveys and reports ENVIRONMENTAL conditions only — trap catch
counts, standing-water/breeding-site presence, observed vector-species
category. It escalates elevated signals to a human public-health
official. **It never diagnoses a disease, never classifies a case, and
never declares an outbreak.** This is enforced in code, not just in
prose: `vectorsurvey.governor/diagnosis-authority-blocked-violations`
is a HARD, PERMANENT, UNCONDITIONAL block — evaluated across every op,
regardless of confidence or rollout phase — on any proposal that
declares a `:diagnosis?` flag or a `:health-determination` field. No
human approval and no phase can ever override this; unlike this
actor's normal high-stakes escalation path, a diagnosis attempt is
rejected outright rather than routed to a human. See
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md)
Decision 1 and `test/vectorsurvey/governor_contract_test.cljc`'s
`diagnosis-authority-is-held-and-permanently-blocked` /
`diagnosis-authority-is-blocked-via-health-determination-alone`.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a sensing robot (vector trap
servicing, water-standing/breeding-site imaging, air/water-quality
sampling) performs the field survey under an actor that proposes a
public-health risk assessment and an independent **Public Health Governor**
that gates it. The governor never dispatches hardware itself;
`:high`/`:safety-critical` findings (e.g. a disease-outbreak-risk signal)
require human sign-off and routing to the public-health authority.

## What this actor does

Proposes **survey/service-visit coordination**, not treatment operation
or clinical judgment:
- `:log-environmental-reading` — site trap-count/standing-water data logging (administrative, not an operational decision, never a clinical claim)
- `:vector-survey-log` — vector-survey finding (ELEVATED-RISK/NORMAL/NEEDS-MORE-DATA), grounded in cited sensor readings for ELEVATED-RISK/NORMAL — an ENVIRONMENTAL finding, never a diagnosis
- `:schedule-service-visit` — trap service-visit scheduling proposal against a site with a verified trap-servicing unit
- `:escalate-outbreak-signal` — surface an elevated vector-risk signal for human public-health-official review (always escalates)

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY**:

- Does NOT diagnose a disease, classify a case, or declare an outbreak — see "NOT a medical or diagnostic authority" above. This is a PERMANENT, unconditional block, independent of and stronger than every other escalation path in this actor.
- Does NOT actuate a treatment/pesticide dispenser directly (any live treatment dispense stays under human public-health authority) — a PERMANENT, unconditional block
- Does NOT assert a site's vector risk ELEVATED-RISK/NORMAL without citing sensor readings that actually cover the required trap-count + standing-water-presence metric surface — an ungrounded condition claim is a HARD violation
- ONLY proposes/coordinates environmental survey and service-visit back-office work; all treatment actuation requires explicit human authority, and all interpretation of an elevated signal is a human public-health official's exclusive call

## Core Contract

```text
site/zone survey request + prior surveillance history
        |
        v
Surveillance Advisor -> Public Health Governor -> report, or human escalation
        |
        v
robot sensing actions (gated) + surveillance record + audit ledger
```

No automated finding can dispatch a robot action the governor refuses,
suppress a surveillance record, downgrade an outbreak-risk signal, or
carry a diagnosis/health-determination through this actor, without
governor approval and audit evidence.

## Architecture

Classic governed-actor pattern (`vectorsurvey.operation/build`, a langgraph-clj StateGraph):
1. **`vectorsurvey.advisor`** (sealed intelligence node, `Surveillance Advisor`): proposes decisions only, never commits
2. **`vectorsurvey.governor`** (independent, `Public Health Governor`): validates against domain rules, re-derived from `vectorsurvey.registry`'s pure functions, `vectorsurvey.telemetry`'s sensor-grounding logic, and `vectorsurvey.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - **Diagnosis/health-determination authority is permanently blocked, unconditionally, across all ops**
     - Site AND trap-unit records must be independently verified/registered (`:verified?` AND `:registered?`) before any service visit is scheduled against them
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct treatment-dispenser control)
     - Directly actuating a treatment/pesticide dispenser (`:actuate-treatment? true`) is a PERMANENT, unconditional block
     - An ELEVATED-RISK/NORMAL vector-survey verdict must be grounded in cited sensor readings covering BOTH `:trap-count` and `:standing-water-presence` (`:needs-more-data` is exempt)
     - No double-scheduling the same service visit
     - No fabricated `:species-observed` value on a vector-survey finding
     - No physically implausible `:trap-count` value on an environmental-reading patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:escalate-outbreak-signal` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`vectorsurvey.phase`** (Phase 0->3 rollout): `:vector-survey-log`/`:schedule-service-visit`/`:escalate-outbreak-signal` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-environmental-reading` may auto-commit at phase 3 when clean
4. **`vectorsurvey.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol -- holds ENVIRONMENTAL survey data only, no personal or clinical health data of any kind

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Capability layer

Resolves via [`kotoba-lang/cofog`](https://github.com/kotoba-lang/cofog)
(COFOG `07.4`). Required capabilities:

- :robotics
- :telemetry
- :identity
- :dmn
- :bpmn
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md),
[`docs/operator-guide.md`](docs/operator-guide.md) and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## License

AGPL-3.0-or-later.
