# cloud-itonami-cofog-07.4

Open COFOG Blueprint for **COFOG 07.4**: Public health services.

This repository designs a forkable OSS business for an independent
community vector-control and environmental-health monitoring contractor: a
sensing robot performs mosquito/vector and environmental-health surveys
under a governor-gated actor, so a municipal public-health department (or
its contracted operator) keeps auditable surveillance records instead of
renting a closed field-service SaaS. Complements
[`cloud-itonami-8691`](https://github.com/cloud-itonami/cloud-itonami-8691)
(Health Access Navigation) at the environmental-surveillance layer.

**Status: design blueprint, no code implemented yet.** This repository
has zero files under `src/` and no `test/` directory — the
Surveillance Advisor and Public Health Governor described below do not
exist in code. It is not (yet) a governed Advisor⊣Governor actuation
actor; the Core Contract section specifies what that pipeline is
intended to enforce once built, not current behavior. See
[`cloud-itonami-isco-1324`](https://github.com/cloud-itonami/cloud-itonami-isco-1324)
for this fleet's minimal implemented reference (`actor`/`advisor`/
`governor`/`store`), and the `cloud-itonami-assoc-*` /
`cloud-itonami-municipality-*` / `cloud-itonami-lei-*` repos for this
fleet's honest not-an-actuation-actor disclaimer pattern.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a sensing robot (vector trap
servicing, water-standing/breeding-site imaging, air/water-quality
sampling) performs the field survey under an actor that proposes a
public-health risk assessment and an independent **Public Health Governor**
that gates it. The governor never dispatches hardware itself;
`:high`/`:safety-critical` findings (e.g. a disease-outbreak-risk signal)
require human sign-off and routing to the public-health authority.

## Core Contract (design intent — not yet implemented)

```text
site/zone survey request + prior surveillance history
        |
        v
Surveillance Advisor -> Public Health Governor -> report, or human escalation
        |
        v
robot sensing actions (gated) + surveillance record + audit ledger
```

**No code exists yet in this repo** — no `src/`, no `test/`, only this
design document plus `blueprint.edn` and `docs/`. Once built, no
automated finding will be able to dispatch a robot action the governor
refuses, suppress a surveillance record, or downgrade an outbreak-risk
signal without governor approval and audit evidence — but none of
that is enforced today.

## Capability layer

Resolves via [`kotoba-lang/cofog`](https://github.com/kotoba-lang/cofog)
(COFOG `07.4`). Required capabilities:

- :robotics
- :telemetry
- :identity
- :dmn
- :bpmn
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
