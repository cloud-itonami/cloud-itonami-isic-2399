# cloud-itonami-isic-2399: Manufacture of other non-metallic mineral products n.e.c.

Open Business Blueprint for **ISIC Rev.5 2399**: manufacture of other non-metallic mineral products n.e.c. — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **plant operations** for this residual mineral-products category.

This repository designs a forkable OSS business for non-metallic
mineral-products plant operations: run by a qualified operator so a
plant keeps its own operating records instead of renting a closed
SaaS.

## Scope: a residual ("n.e.c.") ISIC category, illustrated with one concrete product line

ISIC 2399 is a **residual category** — "other non-metallic mineral
products **not elsewhere classified**." It covers a range of distinct
products with no dedicated ISIC class of their own: mineral wool /
insulation products, abrasive products (grinding wheels, sandpaper),
asbestos products, mica products, and similar non-metallic mineral
goods. Rather than trying to model this whole residual category
abstractly, this build picks **one concrete, illustrative product
line**: a **mineral wool / rock wool thermal-and-acoustic insulation
plant**.

The plant modeled: basalt/diabase/slag raw-material charge → cupola-
melter melting → fiberizing (spinning the melt into fibres) → binder
(typically phenolic resin) application → curing-oven cure →
cutting/forming line — producing batt insulation, roll insulation,
loose-fill insulation, rigid-board insulation, pipe-section
insulation, duct-wrap insulation, acoustic panels and fire-stop wrap.

This is distinct from `cloud-itonami-isic-2391` (Manufacture of
refractory products — a pressing + kiln-firing line producing
high-temperature-service fire brick/kiln lining) and from
`cloud-itonami-isic-2393` (Manufacture of other porcelain and ceramic
products, a separate ceramics vertical with a different feedstock and
firing profile): mineral wool insulation is neither pressed/kiln-fired
ceramic ware nor a refractory lining material — it is a
fibre-forming + binder-cure process with its own distinct hazard
profile. This actor's own hazard profile is centered on the
forming/curing line and the melter: **respirable mineral-fibre
exposure** (man-made vitreous fibre dust at fiberizing, cutting and
packaging — a well-documented respiratory/skin/eye irritation hazard
in this industry), **binder off-gassing/VOC exposure** at the curing
oven, and **forming/curing-line-equipment pinch-point/crush and
thermal/burn hazard** (the cupola melter runs at furnace-service
temperatures; the fiberizing spinner and cutting line carry mechanical
pinch-point risk).

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — forming/curing batch, output-quality (thermal-conductivity/density test) data logging (administrative, not an operational decision)
- `:schedule-maintenance` — forming/curing-line-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a fibre-exposure/equipment-safety concern (always escalates)
- `:coordinate-shipment` — outbound product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(forming/curing-line thermal/burn hazard, respirable mineral-fibre
exposure, binder off-gassing, equipment pinch-point hazard):

- Does NOT control the forming/curing-line equipment directly (melter, fiberizing spinner, binder applicator, curing oven, cutting line)
- Does NOT make plant-safety decisions (that's the plant supervisor's exclusive human authority)
- Does NOT actuate the forming/curing line (human plant supervisor decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`mineralwoolmfg.operation/build`, a langgraph-clj StateGraph):
1. **`mineralwoolmfg.advisor`** (sealed intelligence node, `MineralWoolAdvisor`): proposes decisions only, never commits
2. **`mineralwoolmfg.governor`** (independent, `Mineral Wool Plant Operations Governor`): validates against domain rules, re-derived from `mineralwoolmfg.registry`'s pure functions and `mineralwoolmfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct forming/curing-line-equipment control)
     - Directly actuating the forming/curing line (`:actuate-forming-curing-line? true`) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped weight past its own logged production weight (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-type` value on a production-batch patch
     - No physically implausible `:thermal-conductivity-w-mk` value on a production-batch patch
     - No physically implausible `:density-kg-m3` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`mineralwoolmfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`mineralwoolmfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

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

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
