(ns mineralwoolmfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for `cloud-itonami-isic-2399`: this repo previously had NO demo page
  and no generator at all.

  ## Nothing on the page is hand-written

  This namespace drives the REAL actor stack --
  `mineralwoolmfg.operation` (a langgraph-clj StateGraph) ->
  `mineralwoolmfg.governor` -> `mineralwoolmfg.store` -- via
  `langgraph.graph/run*`, exactly as `mineralwoolmfg.sim`
  (`clojure -M:dev:run`) does, and then renders the RESULT. Every row
  traces to one of:

    - post-run SSoT state   (`store/all-batches`, `store/all-equipment`,
                              `store/all-maintenance`, `store/shipment`,
                              `store/safety-concerns`)
    - the append-only ledger (`store/ledger`)
    - the per-run `:audit` channel returned by `g/run*`
    - a real code value      (`governor/allowed-ops`,
                              `governor/allowed-proposal-effects`,
                              `governor/high-stakes`,
                              `governor/confidence-floor`,
                              `phase/phases`)
    - a registry draft record (`registry/register-maintenance` /
                               `register-shipment` output, as stored)

  The scenario INPUTS (which op against which id) are authored -- that
  is what a scenario is -- but no OUTPUT is. In particular the governor
  rule names, hold details, confidences, draft record ids, shipped-weight
  arithmetic and the phase table are all read back out of the real run,
  never typed in. If a value cannot be sourced from the run it is not
  printed; where a value is structurally unavailable the page SAYS SO
  (see `approver-attribution` below) rather than inventing one.

  ## Determinism

  Nothing in `src/` reads a clock or a RNG; the advisor is a
  deterministic mock (`advisor/mock-advisor`), the registry's record ids
  are zero-padded sequences (`MNT-000000`, `SHP-000000`) and the
  certificates it mints are unsigned constants. Every map/set iterated
  for the page is explicitly sorted here rather than left in hash order.
  The page therefore contains NO timestamp and NO generated id, and two
  consecutive runs are byte-identical.

  ## Build-time invariant

  `-main` THROWS if the run produced no HARD `:governor-hold` fact. A
  console that shows no real hold is not evidence of a governor, so this
  is enforced at build time rather than left as a convention (precedent:
  `cloud-itonami-isic-2513`).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [langgraph.graph :as g]
            [mineralwoolmfg.governor :as governor]
            [mineralwoolmfg.operation :as op]
            [mineralwoolmfg.phase :as phase]
            [mineralwoolmfg.registry :as registry]
            [mineralwoolmfg.store :as store]))

;; ============================ the real run ============================

(def ^:private coordinator
  "Phase-3 plant coordinator -- the same context `mineralwoolmfg.sim`
  uses for its own demo driver."
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(def ^:private trainee
  "A phase-1 (`assisted-intake`) context, used to exercise the ROLLOUT
  PHASE gate independently of the governor: at phase 1 only
  `:log-production-batch` is a permitted write, so a shipment
  coordination that the governor itself would clear is still held by
  `mineralwoolmfg.phase/gate` with `:phase-disabled`."
  {:actor-id "coord-2" :actor-role :plant-coordinator :phase 1})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- resolve! [actor tid status by]
  (g/run* actor {:approval {:status status :by by}} {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded plant (`store/sample-data!`) through a scenario
  that reaches every disposition this actor can produce, and returns
  `{:db <store after the run> :runs [<one entry per request>]}`.

  Each entry is `{:id :label :request :context :state}` where `:state`
  is the FINAL langgraph state for that thread (post-resume where the
  request escalated), so the page can classify each request from its
  own real `:audit` trail rather than from a literal.

  Coverage, in order:

    1-4  the clean path -- a phase-3 auto-commit (`:log-production-batch`,
         the only op in any phase's `:auto` set), then three escalations
         a human APPROVES (maintenance scheduling and shipment
         coordination are never auto-eligible at any phase;
         `:flag-safety-concern` is additionally always high-stakes at
         the governor).
    5    an escalation a human REJECTS -- `:approval-rejected` reaches
         the ledger, so a refusal is as auditable as a commit.
    6-16 ALL TEN of the governor's HARD checks, each holding without
         ever reaching a human. `:shipment-weight-exceeded` is
         exercised TWICE, in both of its distinct modes: genuinely over
         capacity, and NOT COMPUTABLE (a shipment stating no amount --
         un-checkable is not headroom).
    17   the phase gate holding a request the governor itself cleared."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)
        runs (atom [])
        step! (fn [id label request context]
                (let [r (exec! actor id request context)]
                  (swap! runs conj {:id id :label label :request request
                                    :context context :state (:state r)})
                  r))
        finish! (fn [id status by]
                  (let [r (resolve! actor id status by)]
                    (swap! runs
                           (fn [rs] (mapv #(if (= id (:id %))
                                             (assoc % :state (:state r))
                                             %)
                                          rs)))
                    r))]

    ;; ---- 1. clean production-batch logging: phase-3 auto-commit ----
    (step! "r01" "生産バッチ記録更新 (クリーン patch)"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-type :batt-insulation :last-assessed "2026-07-14"}}
           coordinator)

    ;; ---- 2. maintenance scheduling on verified equipment: escalate -> approve ----
    (step! "r02" "保守作業予定 (検証済みファイバライジング成形機)"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "forming-line-001"
                    :maintenance-type :spinner-nozzle-inspection
                    :scheduled-date "2026-08-01"
                    :actuate-forming-curing-line? false}}
           coordinator)
    (finish! "r02" :approved "supervisor-hoshino")

    ;; ---- 3. safety concern: always high-stakes -> escalate -> approve ----
    (step! "r03" "安全懸念報告 (鉱物繊維粉塵)"
           {:op :flag-safety-concern :effect :propose :subject "concern-1"
            :value {:equipment-id "forming-line-001" :severity :moderate
                    :description "ファイバライジング成形機付近の鉱物繊維粉塵滞留の兆候"}}
           coordinator)
    (finish! "r03" :approved "supervisor-hoshino")

    ;; ---- 4. shipment coordination within recorded weight: escalate -> approve ----
    (step! "r04" "出荷調整 (記録済み生産量の範囲内)"
           {:op :coordinate-shipment :effect :propose :subject "ship-1"
            :value {:batch-id "batch-001" :weight-kg 5000.0
                    :destination "buyer-yard-north"}}
           coordinator)
    (finish! "r04" :approved "shipping-approver-aoki")

    ;; ---- 5. escalation the human REJECTS ----
    (step! "r05" "保守作業予定 (人間が却下)"
           {:op :schedule-maintenance :effect :propose :subject "mnt-4"
            :value {:equipment-id "forming-line-001"
                    :maintenance-type :cutting-line-guard-inspection
                    :scheduled-date "2026-08-15"
                    :actuate-forming-curing-line? false}}
           coordinator)
    (finish! "r05" :rejected "supervisor-hoshino")

    ;; ---- 6-16. every HARD governor check, none of which reaches a human ----
    (step! "r06" "誤配線の caller (:effect が :propose でない)"
           {:op :log-production-batch :effect :direct-write :subject "batch-001"
            :patch {:product-type :batt-insulation}}
           coordinator)

    (step! "r07" "許可リスト外の op"
           {:op :actuate-forming-line :effect :propose :subject "batch-001"}
           coordinator)

    (step! "r08" "未検証・未登録のキュアリングオーブンへの保守作業予定"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2"
            :value {:equipment-id "curing-oven-002"
                    :maintenance-type :binder-applicator-inspection
                    :scheduled-date "2026-08-01"
                    :actuate-forming-curing-line? false}}
           coordinator)

    (step! "r09" "未検証・未登録バッチからの出荷調整"
           {:op :coordinate-shipment :effect :propose :subject "ship-2"
            :value {:batch-id "batch-003" :weight-kg 1000.0
                    :destination "buyer-yard-south"}}
           coordinator)

    (step! "r10" "記録済み生産量を超過する出荷調整"
           {:op :coordinate-shipment :effect :propose :subject "ship-3"
            :value {:batch-id "batch-002" :weight-kg 1000.0
                    :destination "buyer-yard-east"}}
           coordinator)

    (step! "r11" "申請量を述べない出荷調整 (空き容量が検算不能)"
           {:op :coordinate-shipment :effect :propose :subject "ship-4"
            :value {:batch-id "batch-001" :destination "buyer-yard-west"}}
           coordinator)

    (step! "r12" "成形/硬化ラインの直接操作(actuate)提案"
           {:op :schedule-maintenance :effect :propose :subject "mnt-3"
            :value {:equipment-id "forming-line-001" :maintenance-type :force-run
                    :scheduled-date "2026-09-01"
                    :actuate-forming-curing-line? true}}
           coordinator)

    (step! "r13" "同一保守作業予定の二重登録"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "forming-line-001"
                    :maintenance-type :spinner-nozzle-inspection
                    :scheduled-date "2026-08-01"
                    :actuate-forming-curing-line? false}}
           coordinator)

    (step! "r14" "捏造された product-type"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-type :unobtainium-insulation}}
           coordinator)

    (step! "r15" "物理的に妥当でない熱伝導率"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:thermal-conductivity-w-mk 9.0}}
           coordinator)

    (step! "r16" "物理的に妥当でない密度"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:density-kg-m3 9999.0}}
           coordinator)

    ;; ---- 17. the phase gate, holding what the governor itself cleared ----
    (step! "r17" "phase 1 での出荷調整 (governor は clean、phase gate が hold)"
           {:op :coordinate-shipment :effect :propose :subject "ship-5"
            :value {:batch-id "batch-001" :weight-kg 100.0
                    :destination "buyer-yard-north"}}
           trainee)

    {:db db :runs @runs}))

;; ============================ derivation ============================

(defn- kw-str
  "Render a keyword with its namespace intact (`name` would silently
  drop `batch/` from `:batch/upsert`)."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- fact-of [audit t]
  (first (filter #(= t (:t %)) audit)))

(defn- holds
  "The `:governor-hold` facts the run actually appended to the ledger."
  [db]
  (filterv #(= :governor-hold (:t %)) (store/ledger db)))

(defn- hard-holds
  "HARD GOVERNOR holds -- a `:governor-hold` fact carrying at least one
  real rule violation. A phase-gate hold also writes a `:governor-hold`
  fact but with an EMPTY `:violations`, so the two are distinguished
  here by what the fact actually carries, not by which run produced it."
  [db]
  (filterv #(seq (:violations %)) (holds db)))

(defn- phase-holds
  "Holds with no governor violation -- these came from the rollout phase
  gate (`mineralwoolmfg.phase/gate`), not from a governor rule."
  [db]
  (filterv #(empty? (:violations %)) (holds db)))

(defn- outcome
  "Classify one real run from its OWN audit trail. Never from a literal."
  [{:keys [state]}]
  (let [audit (:audit state)
        hold (fact-of audit :governor-hold)
        rejected (fact-of audit :approval-rejected)]
    (cond
      rejected {:kind :rejected
                :by (:by (fact-of audit :approval-requested))}

      (and hold (seq (:violations hold)))
      {:kind :hard-hold :violations (:violations hold) :confidence (:confidence hold)}

      hold {:kind :phase-hold
            :reason (:phase-reason hold)
            :phase (:phase hold)}

      (fact-of audit :approval-granted)
      {:kind :approved
       :reason (:reason (fact-of audit :approval-requested))
       :by (:by (fact-of audit :approval-granted))}

      (fact-of audit :approval-requested)
      {:kind :awaiting :reason (:reason (fact-of audit :approval-requested))}

      (fact-of audit :committed) {:kind :auto-commit}
      :else {:kind :other})))

(defn- deep-key-names
  "Every key name appearing anywhere inside `x`, at any depth. Used to
  ASK the real store whether an approver key survived, instead of
  asserting it in prose."
  [x]
  (cond
    (map? x) (into (into #{} (map #(if (keyword? %) (kw-str %) (str %)) (keys x)))
                   (mapcat deep-key-names (vals x)))
    (sequential? x) (into #{} (mapcat deep-key-names x))
    :else #{}))

(defn- approver-attribution
  "DERIVED, at render time, from the real store: where does the human
  approver's id actually end up after a `:request-approval` handoff?

  `operation`'s `:request-approval` node attaches the approver to the
  record's `:payload` as `:approved-by`. Whether that survives depends
  entirely on what THIS repo's `store/commit-record!` reads back out --
  which is exactly the thing that differs between sibling actors in this
  fleet. So this does not hardcode an answer: it walks every register the
  run wrote and checks whether an approver key is actually present, and
  independently checks the ledger. The page renders whatever comes back,
  so the disclosure cannot go stale the day the store is fixed.

  Returns {:approvers :on-record? :on-ledger? :registers-walked}."
  [db runs]
  (let [shipment-ids (into (sorted-set)
                           (keep #(when (= :coordinate-shipment (:op (:request %)))
                                    (:subject (:request %)))
                                 runs))
        registers (concat (store/all-batches db)
                          (store/all-equipment db)
                          (store/all-maintenance db)
                          (keep #(store/shipment db %) shipment-ids)
                          (store/safety-concerns db)
                          (vals (store/get-records db))
                          (store/maintenance-history db)
                          (store/shipment-history db))
        names (deep-key-names registers)
        approver? #(contains? #{"approved-by" "approved_by" "approver"
                                "approved-by-id" "approved_by_id"}
                              (str/lower-case %))]
    {:approvers (vec (sort (into #{} (keep #(:by (fact-of (:audit (:state %))
                                                          :approval-granted))
                                           runs))))
     :on-record? (boolean (some approver? names))
     :on-ledger? (boolean (some #(some approver? (deep-key-names %))
                                (store/ledger db)))
     :registers-walked (count registers)}))

;; ============================ rendering ============================

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- yes-no [b]
  (if b "<span class=\"ok\">yes</span>" "<span class=\"critical\">NO</span>"))

(defn- num [n] (if (number? n) (esc (str n)) "<span class=\"muted\">—</span>"))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- outcome-cell [o]
  (case (:kind o)
    :auto-commit "<span class=\"ok\">auto-commit</span> <span class=\"muted\">(phase 3 :auto)</span>"
    :approved (str "<span class=\"warn\">escalate</span> (" (esc (kw-str (:reason o)))
                   ") &rarr; <span class=\"ok\">approved by " (esc (:by o)) "</span>")
    :rejected "<span class=\"warn\">escalate</span> &rarr; <span class=\"critical\">rejected by human</span>"
    :awaiting "<span class=\"warn\">awaiting human approval</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold</span> &middot; "
                    (str/join ", " (map #(code (kw-str (:rule %))) (:violations o))))
    :phase-hold (str "<span class=\"critical\">phase hold</span> &middot; "
                     (code (kw-str (:reason o)))
                     " <span class=\"muted\">(phase " (esc (:phase o)) ")</span>")
    "<span class=\"muted\">—</span>"))

(defn- batch-rows [db]
  (str/join "\n"
    (for [b (store/all-batches db)
          :let [cap (:weight-kg b)
                shipped (:shipped-weight-kg b 0.0)
                remaining (when (and (number? cap) (number? shipped))
                            (- (double cap) (double shipped)))]]
      (row (code (:id b))
           (esc (:material b))
           (code (kw-str (:product-type b)))
           (num cap)
           (num shipped)
           (num remaining)
           (num (:thermal-conductivity-w-mk b))
           (num (:density-kg-m3 b))
           (yes-no (registry/batch-verified? b))
           (yes-no (registry/batch-registered? b))))))

(defn- equipment-rows [db]
  (str/join "\n"
    (for [e (store/all-equipment db)]
      (row (code (:id e))
           (code (kw-str (:kind e)))
           (yes-no (registry/equipment-verified? e))
           (yes-no (registry/equipment-registered? e))
           (if-let [d (:last-maintenance-date e)] (esc d) "<span class=\"muted\">—</span>")
           (if-let [d (:last-scheduled-maintenance-date e)]
             (str "<span class=\"ok\">" (esc d) "</span>")
             "<span class=\"muted\">—</span>")))))

(defn- request-rows [runs]
  (str/join "\n"
    (for [r runs
          :let [req (:request r)]]
      (row (code (:id r))
           (esc (:label r))
           (code (kw-str (:op req)))
           (code (:subject req))
           (esc (:phase (:context r)))
           (outcome-cell (outcome r))))))

(defn- hold-rule-rows
  "One row per DISTINCT governor rule the run actually tripped, with the
  real count and the real detail string the governor itself produced."
  [db]
  (let [by-rule (->> (hard-holds db)
                     (mapcat :violations)
                     (group-by :rule)
                     (sort-by (comp kw-str key)))]
    (str/join "\n"
      (for [[rule vs] by-rule]
        (row (code (kw-str rule))
             (esc (count vs))
             (esc (:detail (first vs))))))))

(defn- ledger-rows [db]
  (str/join "\n"
    (for [f (store/ledger db)]
      (row (code (kw-str (:t f)))
           (code (kw-str (:op f)))
           (code (:subject f))
           (esc (:actor f))
           (esc (or (:confidence f) ""))
           (esc (if (seq (:basis f))
                  (str/join ", " (map kw-str (:basis f)))
                  ""))))))

(defn- draft-rows [db]
  (str/join "\n"
    (concat
      (for [r (store/maintenance-history db)]
        (row (code (get r "record_id"))
             (code (get r "kind"))
             (code (or (get r "maintenance_id") (get r "shipment_id")))
             (code (or (get r "equipment_id") "—"))
             (esc (get r "immutable"))))
      (for [r (store/shipment-history db)]
        (row (code (get r "record_id"))
             (code (get r "kind"))
             (code (or (get r "maintenance_id") (get r "shipment_id")))
             (code "—")
             (esc (get r "immutable")))))))

(defn- concern-rows [db]
  (str/join "\n"
    (for [c (store/safety-concerns db)]
      (row (code (:id c))
           (code (or (:equipment-id c) "—"))
           (code (kw-str (:severity c)))
           (esc (:description c))))))

(defn- allowlist-rows []
  (str/join "\n"
    (concat
      (for [o (sort-by kw-str governor/allowed-ops)]
        (row (code (kw-str o))
             "許可された op"
             (if (contains? (:auto (get phase/phases 3)) o)
               "<span class=\"ok\">phase 3 で auto-commit 可</span>"
               "<span class=\"warn\">どの phase でも人間承認が必要</span>")))
      (for [e (sort-by kw-str governor/allowed-proposal-effects)]
        (row (code (kw-str e))
             "許可された proposal :effect"
             "<span class=\"muted\">propose 形のみ &middot; 直接操作の effect は恒久的に禁止</span>"))
      (for [s (sort-by kw-str governor/high-stakes)]
        (row (code (kw-str s))
             "high-stakes"
             "<span class=\"warn\">clean でも必ず人間へ escalate</span>")))))

(defn- phase-rows []
  (str/join "\n"
    (for [[n {:keys [label writes auto]}] (sort-by key phase/phases)]
      (row (esc n)
           (code label)
           (if (seq writes)
             (str/join ", " (map #(code (kw-str %)) (sort-by kw-str writes)))
             "<span class=\"muted\">なし (read-only)</span>")
           (if (seq auto)
             (str/join ", " (map #(code (kw-str %)) (sort-by kw-str auto)))
             "<span class=\"muted\">なし</span>")))))

(defn- section [title lead headers body-rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       body-rows "\n"
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

(defn- attribution-section [att]
  (let [{:keys [approvers on-record? on-ledger? registers-walked]} att
        listed (if (seq approvers)
                 (str/join ", " (map #(code %) approvers))
                 "<span class=\"muted\">この run では承認者なし</span>")]
    (str "  <section class=\"card\">\n"
         "    <h2>承認者の帰属 (render 時に実測)</h2>\n"
         "    <p class=\"muted\">この節は「この repo にはこの欠陥がある」と書き置いたものではなく、"
         "レンダリング時に実際の store を歩いて測った結果である &mdash; "
         "store が直された日にこの記述が嘘にならないようにするため。"
         "走査した register 数: <code>" (esc registers-walked) "</code>。</p>\n"
         "    <table>\n"
         "      <thead><tr><th>問い</th><th>実測結果</th></tr></thead>\n"
         "      <tbody>\n"
         (row "この run で実際に承認した人間" listed)
         "\n"
         (row "承認者 id は SSoT の record に残ったか"
              (if on-record?
                "<span class=\"ok\">残った</span>"
                (str "<span class=\"critical\">残っていない</span> &mdash; "
                     "<code>operation</code> は承認者を record の <code>:payload</code> に "
                     "<code>:approved-by</code> として付けるが、この repo の "
                     "<code>store/commit-record!</code> は <code>{:keys [effect path value]}</code> "
                     "しか分解せず <code>:payload</code> を読まない")))
         "\n"
         (row "承認者 id は append-only ledger に残ったか"
              (if on-ledger?
                "<span class=\"ok\">残った</span>"
                (str "<span class=\"critical\">残っていない</span> &mdash; "
                     "<code>:commit</code> node が ledger に追記するのは "
                     "<code>:committed</code> fact だけで、<code>:by</code> を運ぶ "
                     "<code>:approval-granted</code> fact は追記されない")))
         "\n"
         (row "では上の承認者名はどこから来たのか"
              (if (or on-record? on-ledger?)
                "SSoT / ledger から"
                (str "<span class=\"warn\">audit のみ &mdash; store の record には保持されていない</span>"
                     "。各 run の langgraph <code>:audit</code> channel に残る "
                     "<code>:approval-granted</code> fact から join した。"
                     "黙って省略すると「誰も承認していない」と「store が落とした」が"
                     "区別できなくなるので、明示してここに出している")))
         "\n"
         "      </tbody>\n"
         "    </table>\n"
         "  </section>\n")))

(defn render
  "Renders the whole document from a real `run-demo!` result."
  [{:keys [db runs]}]
  (let [att (approver-attribution db runs)
        hh (hard-holds db)
        ph (phase-holds db)]
    (str
     "<!doctype html>\n"
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-2399 &middot; mineral wool plant operations</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>鉱物繊維(ミネラルウール)断熱材工場 プラント運用コーディネーター &mdash; Operator Console</h1>\n"
     "  <span class=\"badge\">ISIC 2399 &middot; read-only sample &middot; governor-gated &middot; "
     "成形/硬化ラインの直接操作は恒久的に禁止</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>この run の要約</h2>\n"
     "    <p class=\"muted\">このページは手書きではない。"
     "<code>mineralwoolmfg.operation</code> (langgraph StateGraph) &rarr; "
     "<code>mineralwoolmfg.governor</code> &rarr; <code>mineralwoolmfg.store</code> を "
     "<code>clojure -M:dev:render-html</code> で実際に走らせた結果を描画している。"
     "行はすべて実行後の SSoT / append-only ledger / 各 run の audit trail に辿れる。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>指標</th><th>実測値</th></tr></thead>\n"
     "      <tbody>\n"
     (row "投入した調整要求" (esc (count runs))) "\n"
     (row "ledger に残った decision fact" (esc (count (store/ledger db)))) "\n"
     (row "HARD governor hold"
          (str "<span class=\"critical\">" (esc (count hh)) "</span> "
               "<span class=\"muted\">(いずれも人間に到達していない)</span>")) "\n"
     (row "phase gate による hold" (esc (count ph))) "\n"
     (row "confidence floor" (code (str governor/confidence-floor))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     (section "生産バッチ (実行後の SSoT)"
              (str "<code>store/all-batches</code> の実行後の状態。"
                   "<code>出荷済み</code> は <code>ship-1</code> が commit した分を含む "
                   "&mdash; store が実際に加算した値であって、ページ側の計算ではない。"
                   "<code>残り</code> はそこから再計算した空き容量。")
              ["バッチ" "材料" "product-type" "生産量 kg" "出荷済み kg" "残り kg"
               "λ W/(m·K)" "密度 kg/m³" "QC 検証済み" "登録済み"]
              (batch-rows db))

     (section "成形/硬化ライン設備 (実行後の SSoT)"
              (str "<code>store/all-equipment</code> の実行後の状態。"
                   "未検証または未登録の設備への保守作業予定は "
                   "<code>:equipment-not-verified</code> で HARD hold される。")
              ["設備" "種別" "検証済み" "登録済み" "前回保守日" "予定された保守日"]
              (equipment-rows db))

     (section "この run が投入した調整要求"
              (str "各行の判定は、その run 自身の langgraph <code>:audit</code> trail から"
                   "導出したもの &mdash; ページ側に書いた文字列ではない。")
              ["run" "シナリオ" "op" "対象" "phase" "判定"]
              (request-rows runs))

     (section "発火した governor の HARD ルール"
              (str "ledger 上の <code>:governor-hold</code> fact から集計。"
                   "detail は governor 自身が生成した文字列をそのまま出している。"
                   "HARD hold は override 不能で、人間の承認画面にも到達しない。")
              ["ルール" "発火回数" "governor が出した detail (最初の1件)"]
              (hold-rule-rows db))

     (section "governor の閉じた契約"
              (str "<code>governor/allowed-ops</code> / "
                   "<code>allowed-proposal-effects</code> / <code>high-stakes</code> の"
                   "実際の値。この4つ以外の op も effect も通らない。")
              ["値" "種別" "扱い"]
              (allowlist-rows))

     (section "段階的ロールアウト (phase gate)"
              (str "<code>phase/phases</code> の実際の値。"
                   "<code>:schedule-maintenance</code> はどの phase の <code>:auto</code> にも"
                   "属さない &mdash; ロールアウトの途中段階ではなく恒久的な構造。")
              ["phase" "ラベル" "書き込みが許される op" "auto-commit が許される op"]
              (phase-rows))

     (section "発行された DRAFT 記録"
              (str "<code>registry/register-maintenance</code> / "
                   "<code>register-shipment</code> が実際に発行し store に入った記録。"
                   "いずれも UNSIGNED の draft &mdash; 署名は人間の行為であって"
                   "この actor の行為ではない。")
              ["record_id" "kind" "対象" "設備" "immutable"]
              (draft-rows db))

     (section "安全懸念 (append-only)"
              (str "<code>store/safety-concerns</code>。安全懸念は設備/バッチの"
                   "検証状態で決してブロックされない &mdash; 未検証の設備についても"
                   "報告できる。")
              ["id" "設備" "深刻度" "内容"]
              (concern-rows db))

     (attribution-section att)

     (section "監査 ledger (この run)"
              (str "<code>store/ledger</code> の全件。append-only。"
                   "commit も hold も却下も、同じ 1 本のログに残る。")
              ["fact" "op" "対象" "actor" "confidence" "basis"]
              (ledger-rows db))

     "</main>\n"
     "<footer>\n"
     "  <p>生成: <code>clojure -M:dev:render-html</code> "
     "(<code>mineralwoolmfg.render-html</code>)。"
     "タイムスタンプも生成 id も含まないため、同じ seed に対して連続実行は byte 一致する。</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hh (hard-holds db)]
    ;; Build-time invariant, not a convention: a console that shows no
    ;; real HARD hold is not evidence of a governor, so refuse to write
    ;; one. (Precedent: cloud-itonami-isic-2513.)
    (when (empty? hh)
      (throw (ex-info (str "no HARD :governor-hold fact on the ledger — "
                           "refusing to write a console that shows no real hold")
                      {:ledger-facts (count (store/ledger db))
                       :runs (count runs)
                       :governor-holds (count (holds db))})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count runs) " requests, "
                  (count (store/ledger db)) " ledger facts, "
                  (count hh) " HARD governor holds, "
                  (count (phase-holds db)) " phase holds)"))))
