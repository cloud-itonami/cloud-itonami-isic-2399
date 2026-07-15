(ns mineralwoolmfg.registry
  "Pure-function domain logic for the ISIC 2399 (Manufacture of other
  non-metallic mineral products n.e.c.) plant-operations coordination
  actor, illustrated concretely with a mineral wool / rock wool
  insulation plant -- equipment/batch verification, shipment-weight
  recompute, product-type validation, thermal-conductivity plausibility
  validation, density plausibility validation, and draft
  maintenance-schedule/shipment-coordination record construction.

  Per docs/adr/0001-architecture.md Decision 1: this vertical has NO
  pre-existing `kotoba-lang/mineralwoolmfg`-style capability library to
  wrap (verified: no such repo exists). The domain logic therefore
  lives here as pure functions, re-verified INDEPENDENTLY by
  `mineralwoolmfg.governor` -- the same 'ground truth, not self-report'
  discipline every sibling actor's own registry establishes (e.g.
  `refractorymfg.registry/shipment-weight-exceeded?` from
  `cloud-itonami-isic-2391`, the closest architectural sibling): never
  trust a proposal's own self-reported weight/status when the inputs
  needed to recompute it independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real plant-operations system. It builds the DRAFT record
  a plant coordinator would keep (a scheduled maintenance window, a
  coordinated shipment), not the act of actuating the forming/curing
  line, or dispatching a real freight carrier (this actor NEVER does
  either -- see README `What this actor does NOT do`).

  SCOPE NOTE: ISIC 2399 (this actor) is a RESIDUAL category -- 'other
  non-metallic mineral products n.e.c.' (not elsewhere classified) --
  covering mineral wool/insulation, abrasive products, asbestos
  products, mica products and similar non-metallic mineral goods with
  no dedicated ISIC class of their own. This build picks ONE concrete,
  illustrative product line rather than trying to model the whole
  residual category abstractly: a mineral wool / rock wool insulation
  plant (basalt/diabase/slag raw-material charge -> cupola-melter
  melting -> fiberizing (spinning the melt into fibres) -> binder
  (typically phenolic resin) application -> curing-oven cure ->
  cutting/forming line) producing batt, roll, loose-fill, rigid-board,
  pipe-section, duct-wrap, acoustic-panel and fire-stop-wrap thermal/
  acoustic insulation products. This is distinct from
  `cloud-itonami-isic-2391` (Manufacture of refractory products --
  high-temperature-service fire brick/kiln lining, a pressing+kiln-
  firing line) and from `cloud-itonami-isic-2393` (Manufacture of
  other porcelain and ceramic products, a separate ceramics vertical
  with a different feedstock and firing profile): mineral wool
  insulation is neither pressed/kiln-fired ceramic ware nor a
  refractory lining material -- it is a fibre-forming + binder-cure
  process with its own distinct hazard profile. This actor's own hazard
  profile is centered on the forming/curing line and the melter:
  respirable mineral-fibre exposure (man-made vitreous fibre dust at
  fiberizing, cutting and packaging -- a well-documented respiratory/
  skin/eye irritation hazard in this industry), binder off-gassing/VOC
  exposure at the curing oven, and forming/curing-line-equipment
  pinch-point/crush and thermal/burn hazard (the cupola melter runs at
  furnace-service temperatures; the fiberizing spinner and cutting
  line carry mechanical pinch-point risk)."
  )

;; ----------------------------- constants -----------------------------

(def valid-product-types
  "The closed set of product-type values a production batch (a
  forming/curing batch) record may declare -- the standard mineral
  wool / rock wool insulation product families this actor's plant may
  produce. Anything else is a fabricated/unrecognized product type --
  the governor HARD-holds rather than let an invented product type
  pass through."
  #{:batt-insulation :roll-insulation :loose-fill-insulation
    :rigid-board-insulation :pipe-section-insulation :duct-wrap-insulation
    :acoustic-panel :fire-stop-wrap})

(def thermal-conductivity-min-w-mk
  "Physical floor for a batch's own thermal-conductivity (lambda / λ)
  reading in W/(m·K) (zero conductivity is not a physically real
  insulation product, but the floor is set at a value below the
  lowest-conductivity mineral wool products actually manufactured, so
  a genuine reading is never rejected)."
  0.020)

(def thermal-conductivity-max-w-mk
  "Physical ceiling for a batch's own thermal-conductivity (lambda / λ)
  reading in W/(m·K) -- no known mineral wool insulation product tested
  per e.g. ASTM C518/ISO 8301 exceeds this value at manufacturing QC. A
  reading above this is implausible gauge/QC data, not a real batch."
  0.060)

(def density-min-kg-m3
  "Physical floor for a batch's own bulk-density reading in kg/m^3
  (zero density is not a physically real batch, never negative)."
  8.0)

(def density-max-kg-m3
  "Physical ceiling for a batch's own bulk-density reading in kg/m^3 --
  no known mineral wool insulation product (even the highest-density
  rigid board) exceeds this value. A reading above this is implausible
  sensor/QC data, not a real batch."
  250.0)

;; ----------------------------- equipment checks -----------------------------

(defn equipment-verified?
  "Ground-truth check: has `equipment`'s own record been marked
  verified (i.e. it has actually been inspected/commissioned and
  registered in the SSoT, not merely referenced from an unverified
  maintenance request)? A pure predicate over the equipment's own
  permanent field -- no proposal inspection needed."
  [equipment]
  (true? (:verified? equipment)))

(defn equipment-registered?
  "Ground-truth check: does `equipment`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the plant's
  equipment registry)? Scheduling maintenance against equipment that
  is not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [equipment]
  (true? (:registered? equipment)))

(defn equipment-ready?
  "Combined ground-truth gate: the equipment must be both `verified?`
  AND `registered?` before ANY maintenance may be scheduled against
  it. Two independent facts on the equipment's own permanent record,
  neither inferred from the advisor's own rationale."
  [equipment]
  (and (equipment-verified? equipment) (equipment-registered? equipment)))

;; ----------------------------- batch checks -----------------------------

(defn batch-verified?
  "Ground-truth check: has `batch`'s own record been marked verified
  (i.e. its product-type/weight/thermal-conductivity/density claims
  have actually been QC-inspected, not merely logged from an
  unverified intake patch)?"
  [batch]
  (true? (:verified? batch)))

(defn batch-registered?
  "Ground-truth check: is `batch`'s own record on file in the plant's
  production ledger? Coordinating a shipment against a batch that is
  not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [batch]
  (true? (:registered? batch)))

(defn batch-ready?
  "Combined ground-truth gate: the batch must be both `verified?` AND
  `registered?` before ANY shipment may be coordinated against it."
  [batch]
  (and (batch-verified? batch) (batch-registered? batch)))

(defn shipment-weight-exceeded?
  "Ground-truth check for a `:coordinate-shipment` proposal:
  would `shipped-to-date-kg` + `new-weight-kg` exceed `batch`'s own
  recorded `:weight-kg` (the batch's own logged production weight)?
  Needs no proposal inspection or stored-verdict lookup -- its inputs
  are permanent fields already on the batch's own record, the same
  shape every sibling actor's own cost/total-matching check uses."
  [batch new-weight-kg]
  (let [capacity (:weight-kg batch)
        so-far (:shipped-weight-kg batch 0.0)]
    (and (number? capacity)
         (number? new-weight-kg)
         (> (+ (double so-far) (double new-weight-kg)) (double capacity)))))

(defn product-type-valid?
  "Is `product-type` one of the closed, known mineral wool insulation
  product values? nil/blank is treated as invalid (a production-batch
  patch must declare a real product type, not omit it silently)."
  [product-type]
  (contains? valid-product-types product-type))

(defn thermal-conductivity-valid?
  "Is `w-mk` a physically plausible batch thermal-conductivity (lambda
  / λ) reading, in W/(m·K)? Rejects nil, non-numbers, negative values,
  and values beyond `thermal-conductivity-max-w-mk` -- a fabricated or
  gauge-error reading, never let through as a real batch fact."
  [w-mk]
  (and (number? w-mk)
       (>= (double w-mk) thermal-conductivity-min-w-mk)
       (<= (double w-mk) thermal-conductivity-max-w-mk)))

(defn density-valid?
  "Is `kg-m3` a physically plausible batch bulk-density reading, in
  kg/m^3? Rejects nil, non-numbers, negative values, and values beyond
  `density-max-kg-m3` -- a fabricated or sensor-error reading, never
  let through as a real batch fact."
  [kg-m3]
  (and (number? kg-m3)
       (>= (double kg-m3) density-min-kg-m3)
       (<= (double kg-m3) density-max-kg-m3)))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human plant supervisor's/shipping approver's act, not this
  actor's."
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

(defn register-maintenance
  "Validate + construct the MAINTENANCE-SCHEDULE DRAFT -- a proposed
  forming/curing-line-equipment maintenance window against a verified,
  registered piece of equipment. Pure function -- does not actuate the
  forming/curing line or execute any maintenance; it builds the RECORD
  a plant coordinator would keep. `mineralwoolmfg.governor`
  independently re-verifies the equipment's own verified/registered
  ground truth, and permanently blocks any attempt to directly
  actuate the forming/curing line (see README `Actuation`), before
  this is ever allowed to commit."
  [maintenance-id equipment-id sequence]
  (when-not (and maintenance-id (not= maintenance-id ""))
    (throw (ex-info "maintenance: maintenance_id required" {})))
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info "maintenance: equipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "maintenance: sequence must be >= 0" {})))
  (let [maintenance-number (str "MNT-" (zero-pad sequence 6))
        record {"record_id" maintenance-number
                "kind" "maintenance-schedule-draft"
                "maintenance_id" maintenance-id
                "equipment_id" equipment-id
                "immutable" true}]
    {"record" record "maintenance_number" maintenance-number
     "certificate" (unsigned-certificate "MaintenanceSchedule" maintenance-number maintenance-number)}))

(defn register-shipment
  "Validate + construct the SHIPMENT-COORDINATION DRAFT -- a proposed
  outbound mineral wool insulation shipment against a verified,
  registered production batch. Pure function -- does not dispatch any
  real freight carrier; it builds the RECORD a plant coordinator would
  keep. `mineralwoolmfg.governor` independently re-verifies the
  shipment's own claimed weight against `shipment-weight-exceeded?`,
  before this is ever allowed to commit."
  [shipment-id sequence]
  (when-not (and shipment-id (not= shipment-id ""))
    (throw (ex-info "shipment: shipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "shipment: sequence must be >= 0" {})))
  (let [shipment-number (str "SHP-" (zero-pad sequence 6))
        record {"record_id" shipment-number
                "kind" "shipment-coordination-draft"
                "shipment_id" shipment-id
                "immutable" true}]
    {"record" record "shipment_number" shipment-number
     "certificate" (unsigned-certificate "ShipmentCoordination" shipment-number shipment-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
