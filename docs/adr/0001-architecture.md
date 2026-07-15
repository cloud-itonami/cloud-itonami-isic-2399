# ADR-0001: MineralWoolAdvisor ⊣ Mineral Wool Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2399` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2399` publishes an OSS blueprint for ISIC 2399
(Manufacture of other non-metallic mineral products n.e.c.) plant
**operations coordination**. ISIC 2399 is a residual ("not elsewhere
classified") category spanning mineral wool/insulation, abrasive
products, asbestos products, mica products and similar non-metallic
mineral goods with no dedicated ISIC class of their own. Like every
actor in this fleet, the blueprint alone is not an implementation:
this ADR records the governed-actor architecture that promotes it to
real, tested code, following the same langgraph StateGraph +
independent Governor + Phase 0->3 rollout pattern established across
the cloud-itonami fleet, and records the decision to pick ONE
concrete, illustrative product line (production-batch data logging
[product-type/weight/thermal-conductivity/density], forming/curing-
line-equipment maintenance scheduling, safety-concern flagging, and
outbound shipment coordination) rather than attempt to model the
residual category abstractly.

The closest architectural analog is `cloud-itonami-isic-2391`
(Manufacture of refractory products): both are back-office
coordination actors for a fixed processing PLANT with heavy
process-line equipment and a real physical safety dimension, and both
share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-
concern`/`:coordinate-shipment`) and the same two-entity verified/
registered gate structure (equipment for maintenance scheduling, batch
for shipment coordination). The two verticals are, however, distinct
plants with distinct hazard profiles and distinct product/quality
vocabularies: 2391's central physical hazard is a hotter,
higher-temperature-service kiln-firing line (pressing + kiln-firing
producing fire brick/kiln lining) plus refractory-dust exposure, while
2399's illustrative mineral-wool plant hazard is respirable
mineral-fibre exposure (man-made vitreous fibre dust at fiberizing,
cutting and packaging), binder off-gassing/VOC exposure at the curing
oven, and forming/curing-line-equipment pinch-point/crush and
thermal/burn hazard at the cupola melter. This build mirrors 2391's
architecture closely but adapts the hazard profile and equipment/
product vocabulary to the mineral-wool-insulation plant: 2399's
permanent equipment-actuation block guards a forming/curing line
(`:actuate-forming-curing-line?`) rather than a pressing-line/kiln
line (`:actuate-kiln-pressing-line?`); and 2399's production-batch
record declares a `:product-type` (spanning batt, roll, loose-fill,
rigid-board, pipe-section, duct-wrap, acoustic-panel and fire-stop-wrap
insulation product families), a `:thermal-conductivity-w-mk` reading
(lambda / λ value per e.g. ASTM C518/ISO 8301, an insulation-specific
quality data point with no direct 2391 analog -- mineral wool products
are graded by their thermal resistance, unlike thermal-shock-crack
resistance for refractory brick), and a `:density-kg-m3` reading,
rather than 2391's `:thermal-shock-cycles`/`:cold-crushing-strength-
mpa` pair.

`cloud-itonami-isic-2399` is also distinct from `cloud-itonami-isic-2393`
(Manufacture of other porcelain and ceramic products, a separate
ceramics vertical with a different feedstock and firing profile) --
which this build does not depend on or wrap.

This vertical has NO pre-existing `kotoba-lang/mineralwoolmfg`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic -- pure functions in
`mineralwoolmfg.registry` (equipment/batch verification, shipment-
weight recompute, product-type validation, thermal-conductivity
plausibility validation, density plausibility validation) are
re-verified independently by the governor, the same "ground truth, not
self-report" discipline established across prior actors (most directly
`cloud-itonami-isic-2391`'s `refractorymfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:mineral-wool-plant-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "mineral-wool-plant-operations-governor"
--owner cloud-itonami`, zero hits before this repo was created); the
`mineralwoolmfg` namespace prefix is likewise grep-verified UNIQUE
fleet-wide (`gh search code "mineralwoolmfg" --owner cloud-itonami`,
zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external mineral-wool-insulation capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
mineral-wool-insulation vertical has NO pre-existing capability
library to wrap. The equipment/batch-verification / shipment-weight /
product-type / thermal-conductivity / density validation functions
live as pure functions in `mineralwoolmfg.registry` and are
re-verified independently by `mineralwoolmfg.governor` -- the same
"ground truth, not self-report" discipline established across prior
actors (most directly `cloud-itonami-isic-2391`'s `refractorymfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of mineral-wool-
insulation plant operations (illustrating the broader ISIC 2399
residual category). It does NOT:
- Control the forming/curing-line equipment directly (melter, fiberizing spinner, binder applicator, curing oven, cutting line)
- Make plant-safety decisions (exclusive to the human plant supervisor)
- Actuate the forming/curing line

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority --
it is a proposal-screening and documentation layer.

**CRITICAL SAFETY BOUNDARY**: mineral-wool-insulation manufacturing is
a safety-critical domain (forming/curing-line thermal/burn hazard at
the cupola melter, respirable mineral-fibre exposure, binder
off-gassing/VOC exposure, equipment pinch-point hazard, heavy material
handling). Safety-concern flagging NEVER auto-commits. All safety
concerns escalate immediately to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (respirable mineral-fibre-exposure hazard,
binder off-gassing/VOC hazard, forming/curing-line-equipment safety
concern, crew fatigue) ALWAYS escalates, never auto-commits. This is
not a "low-stakes proposal" -- it is a circuit-breaker that must reach
human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2391`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own `:verified?`/
`:registered?` fields; `:coordinate-shipment` independently verifies
the referenced **batch**'s own `:verified?`/`:registered?` fields.
Both are the same "plant/batch record must be independently verified/
registered before any action" HARD invariant applied to the two
distinct record kinds this domain actually has.
`:coordinate-shipment` additionally independently recomputes whether a
batch's own recorded shipped-to-date weight plus the proposal's own
claimed weight would exceed the batch's own recorded production
weight -- never taken on the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into eleven concrete checks
in `mineralwoolmfg.governor`, matching `cloud-itonami-isic-2391`'s own
eleven -- this vertical's `:thermal-conductivity-w-mk` plausibility
check replaces 2391's `:thermal-shock-cycles` check, and
`:density-kg-m3` replaces `:cold-crushing-strength-mpa`, per Decision
1's own field-vocabulary decision above) block proposals and cannot be
overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's weight must independently recompute within the batch's own logged production weight
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct forming/curing-line-equipment control or actuation is permanently blocked
4. The op allowlist is closed -- `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Non-metallic mineral products (illustrated with mineral-wool-
insulation) plant operations back-office now has a documented,
governed, auditable coordination layer that funnels all decisions
through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into eleven concrete governor checks) protect against scope creep into
unauthorized equipment operation or forming/curing-line actuation.
Safety concerns are a circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation and forming/curing-line operation
remain human-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch) -- this is a standalone
coordinator blueprint.

(-) Chose ONE illustrative product line (mineral wool / rock wool
insulation) for a residual ("n.e.c.") ISIC category that in reality
spans several distinct product families (abrasive products, asbestos
products, mica products) with their own hazard profiles and
vocabularies not modeled here -- a fork targeting one of those other
product families would need its own registry/governor field
vocabulary, following this same pattern.

## Verification

- `cloud-itonami-isic-2399`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-weight-exceeded, forming-curing-line-
  actuate-blocked, already-scheduled, invalid-product-type, invalid-
  thermal-conductivity, invalid-density).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
