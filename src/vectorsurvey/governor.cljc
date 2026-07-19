(ns vectorsurvey.governor
  "Public Health Governor -- the independent compliance layer that
  earns the Surveillance Advisor the right to commit.

  **CRITICAL, DOMAIN-DEFINING INVARIANT: this actor is NEVER a medical
  or diagnostic authority.** `diagnosis-authority-blocked-violations`
  below is a HARD, PERMANENT, UNCONDITIONAL block on ANY proposal (any
  op, regardless of confidence or phase) whose own `:value`/`:patch`
  declares a `:diagnosis?` true flag or a non-nil `:health-
  determination` field. No human approval and no phase can ever
  override this -- this actor surveys and reports ENVIRONMENTAL
  conditions (trap counts, standing-water/breeding-site presence,
  observed vector-species category) only. It escalates elevated
  signals to a human public-health official; it never itself issues a
  disease diagnosis, a case classification, or an outbreak
  declaration. See README `What this actor does NOT do` and
  `vectorsurvey.registry` ns docstring.

  Beyond that domain-defining boundary, the advisor also has no notion
  of whether a site it wants to schedule a service visit against has
  actually been surveyed/registered, whether a trap-servicing unit it
  relies on has actually been inspected/registered, whether an
  ELEVATED-RISK/NORMAL verdict it filed is actually grounded in
  measured sensor readings rather than self-attested, whether a
  service-visit proposal secretly tries to ACTUATE (rather than merely
  schedule) a treatment/pesticide dispenser, or when an act stops being
  a survey-coordination proposal and becomes direct treatment-
  dispenser control, so this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD.

  `:itonami.blueprint/governor` is `:public-health-governor` (see
  docs/adr/0001-architecture.md).

  Checks below, ALL HARD violations except the confidence/high-stakes
  gate (SOFT -- asks a human to look, and the human may approve):

    1. Request-level propose-only  -- did the CALLER's own request
                                       actually declare `:effect
                                       :propose`? Any other value is a
                                       mis-wired/compromised caller
                                       trying to bypass proposal-only
                                       mode -- HARD, unconditional,
                                       evaluated BEFORE anything else.
    2. Closed op allowlist         -- is `:op` one of the four ops this
                                       actor is authorized to
                                       coordinate? Anything else --
                                       HARD hold.
    3. Diagnosis-authority blocked -- ANY proposal (any op) whose own
                                       `:value`/`:patch` declares
                                       `:diagnosis? true` or a non-nil
                                       `:health-determination` --
                                       PERMANENT, unconditional, THE
                                       domain-defining scope boundary
                                       (see ns docstring above).
                                       Evaluated UNCONDITIONALLY (not
                                       scoped to a specific op), the
                                       SAME discipline
                                       `motomfg.governor/certification-
                                       authority-blocked-violations`
                                       established for a different
                                       domain's own permanent authority
                                       boundary.
    4. Closed effect allowlist     -- is the PROPOSAL's own `:effect`
                                       (what would actually commit) one
                                       of the four propose-shaped
                                       effects? A proposal effect
                                       outside this set (e.g. a
                                       hallucinated `:dispenser/
                                       actuate`) is the 'direct
                                       treatment-dispenser control'
                                       scope violation this actor must
                                       NEVER perform -- HARD,
                                       PERMANENT, unconditional.
    5. Treatment-actuate blocked   -- for `:schedule-service-visit`,
                                       does the proposal's own `:value`
                                       declare `:actuate-treatment?
                                       true`? Directly actuating a
                                       treatment/pesticide dispenser is
                                       this actor's other permanent
                                       scope boundary (see README `What
                                       this actor does NOT do`) --
                                       HARD, PERMANENT, unconditional.
                                       NO phase and NO human approval
                                       can ever override this (see
                                       `vectorsurvey.phase`: this op is
                                       never a member of any phase's
                                       `:auto` set either -- two
                                       independent layers agree).
    6. Survey-verdict ungrounded   -- for `:vector-survey-log`, when
                                       the proposal's own `:value`
                                       declares a `:verdict` of
                                       `:elevated-risk` or `:normal`,
                                       INDEPENDENTLY re-derive whether
                                       the cited `:sensor-basis`
                                       reading-ids actually ground that
                                       verdict via
                                       `vectorsurvey.telemetry/
                                       grounds-verdict?` -- never trust
                                       the advisor's own claim that its
                                       basis is sufficient. A
                                       `:needs-more-data` verdict is
                                       exempt (an honest 'I could not
                                       determine' needs no basis).
    7. Site not verified/
       registered                  -- for `:schedule-service-visit`,
                                       INDEPENDENTLY verify the
                                       referenced site's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`vectorsurvey.registry/site-
                                       ready?`) -- never trust the
                                       advisor's own rationale about
                                       verification/registration
                                       status.
    8. Trap unit not verified/
       registered                  -- for `:schedule-service-visit`,
                                       INDEPENDENTLY verify the
                                       referenced trap-servicing unit's
                                       own `:verified?` AND
                                       `:registered?` are both true
                                       (`vectorsurvey.registry/trap-
                                       unit-ready?`) -- never trust the
                                       advisor's own rationale.
    9. Already scheduled           -- for `:schedule-service-visit`,
                                       refuses to schedule the SAME
                                       service visit twice, off a
                                       dedicated `:scheduled?` fact
                                       (never a `:status` value).
   10. Invalid vector species      -- for `:vector-survey-log`, if the
                                       value declares a
                                       `:species-observed` outside the
                                       closed known set
                                       (`vectorsurvey.registry/vector-
                                       species-valid?`), the survey
                                       finding is rejected rather than
                                       let a fabricated species-
                                       observation category through.
   11. Invalid vector count        -- for `:log-environmental-reading`,
                                       if the patch declares a
                                       `:trap-count` that is not a
                                       physically plausible reading
                                       (`vectorsurvey.registry/vector-
                                       count-valid?`), the site record
                                       is rejected rather than let
                                       fabricated/miscounted data
                                       through.
   12. Confidence floor / high-
       stakes gate                  -- LLM confidence below threshold,
                                       OR the proposal's own `:stake`
                                       is in `high-stakes`
                                       (`:vector/outbreak-risk-signal`,
                                       ALWAYS set for
                                       `:escalate-outbreak-signal`) --
                                       escalate to a human
                                       public-health official. SOFT:
                                       the human may approve."
  (:require [vectorsurvey.registry :as registry]
            [vectorsurvey.store :as store]
            [vectorsurvey.telemetry :as telemetry]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`."
  #{:log-environmental-reading :vector-survey-log
    :schedule-service-visit :escalate-outbreak-signal})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- all four are propose-shaped drafts, NEVER a direct
  treatment-dispenser-control effect."
  #{:site/upsert :survey/log-set
    :service-visit/schedule :outbreak-signal/escalate})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Outbreak-risk signals are the one op in this domain that always
  demands human public-health-official eyes regardless of confidence."
  #{:vector/outbreak-risk-signal})

(def condition-verdicts
  "Vector-survey-log verdicts that assert an ENVIRONMENTAL condition
  and therefore REQUIRE sensor grounding. `:needs-more-data` is
  deliberately absent -- the honest 'I don't know yet' outcome needs no
  basis. Neither this set nor anything in this namespace ever names a
  disease or clinical verdict -- see `diagnosis-authority-blocked-
  violations`."
  #{:elevated-risk :normal})

;; ----------------------------- checks -----------------------------

(defn- no-propose-effect-violations
  "HARD, unconditional, evaluated first: the caller's own request MUST
  declare `:effect :propose` -- any other value is a mis-wired or
  compromised caller trying to bypass proposal-only mode."
  [{:keys [effect]}]
  (when (not= effect :propose)
    [{:rule :not-propose-effect
      :detail (str "request :effect は :propose のみ許可 (受信値: " (pr-str effect) ")")}]))

(defn- unknown-op-violations
  "HARD: `:op` must be one of the closed allowlist this actor
  coordinates -- never route an unrecognized operation."
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :unknown-op
      :detail (str op " はこの actor が扱う操作の許可リストに無い")}]))

(defn- diagnosis-authority-blocked-violations
  "HARD, PERMANENT, unconditional: ANY proposal (any op) whose own
  `:value`/`:patch` declares `:diagnosis? true` or a non-nil
  `:health-determination` is attempting to issue a medical/diagnostic
  determination -- an authority exclusively reserved to a licensed
  public-health/medical official, NEVER this actor. THE domain-
  defining scope boundary (see ns docstring). No phase and no human
  approval can ever override this."
  [proposal]
  (let [payload (or (:value proposal) (:patch proposal))]
    (when (or (true? (:diagnosis? payload)) (some? (:health-determination payload)))
      [{:rule :diagnosis-authority-blocked
        :detail "疾病診断・健康判定の自己発行は恒久的に禁止 -- 公衆衛生当局・医療専門家の専権事項。この actor は環境観測の報告のみ行う"}])))

(defn- treatment-dispenser-control-blocked-violations
  "HARD, PERMANENT: the proposal's own `:effect` -- what would actually
  commit -- must be within the closed propose-shaped effect allowlist.
  Anything else (direct treatment/pesticide-dispenser control, a
  fabricated actuation effect) is this actor's central scope
  boundary."
  [proposal]
  (when-not (contains? allowed-proposal-effects (:effect proposal))
    [{:rule :treatment-dispenser-control-blocked
      :detail (str "proposal :effect (" (pr-str (:effect proposal))
                   ") は処理・薬剤散布装置の直接操作に該当する可能性があり、恒久的に禁止")}]))

(defn- treatment-actuate-blocked-violations
  "HARD, PERMANENT, unconditional: a `:schedule-service-visit` proposal
  whose own `:value` declares `:actuate-treatment? true` is attempting
  to directly actuate a treatment/pesticide dispenser -- this actor may
  only ever propose/schedule a DRAFT service visit, never actuate the
  dispenser directly. No override, ever."
  [{:keys [op]} proposal]
  (when (and (= op :schedule-service-visit)
             (true? (:actuate-treatment? (:value proposal))))
    [{:rule :treatment-actuate-blocked
      :detail "処理・薬剤散布装置の直接操作(actuate)提案は恒久的に禁止 -- 提案(draft)のみ許可"}]))

(defn- survey-verdict-ungrounded-violations
  "For `:vector-survey-log`, when the proposal's own `:value` declares
  a `:verdict` of `:elevated-risk` or `:normal`, INDEPENDENTLY
  re-derive whether the cited `:sensor-basis` actually grounds that
  verdict -- never trust the advisor's own claim. `:needs-more-data`
  is exempt. This grounds an ENVIRONMENTAL condition ONLY -- never a
  disease diagnosis (see `diagnosis-authority-blocked-violations`)."
  [{:keys [op]} proposal st]
  (when (= op :vector-survey-log)
    (let [{:keys [site-id verdict sensor-basis]} (:value proposal)]
      (when (and (contains? condition-verdicts verdict)
                 (not (telemetry/grounds-verdict?
                       site-id sensor-basis (store/readings-for-site st site-id))))
        [{:rule :survey-verdict-ungrounded
          :detail (str verdict " 判定には trap-count と standing-water-presence 両方をカバーする"
                       "実測センサー引用が必要 -- 引用不足または未検証")}]))))

(defn- site-not-verified-violations
  "For `:schedule-service-visit`, INDEPENDENTLY verify the referenced
  site exists and is both `:verified?` AND `:registered?` -- never
  trust the advisor's own report."
  [{:keys [op]} proposal st]
  (when (= op :schedule-service-visit)
    (let [site-id (:site-id (:value proposal))
          s (and site-id (store/site st site-id))]
      (when-not (and s (registry/site-ready? s))
        [{:rule :site-not-verified
          :detail (str site-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済みサイト記録が無い状態での訪問提案")}]))))

(defn- trap-unit-not-verified-violations
  "For `:schedule-service-visit`, INDEPENDENTLY verify the referenced
  trap-servicing unit exists and is both `:verified?` AND
  `:registered?` -- never trust the advisor's own report."
  [{:keys [op]} proposal st]
  (when (= op :schedule-service-visit)
    (let [trap-id (:trap-id (:value proposal))
          u (and trap-id (store/trap-unit st trap-id))]
      (when-not (and u (registry/trap-unit-ready? u))
        [{:rule :trap-unit-not-verified
          :detail (str trap-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済みトラップユニット記録が無い状態での訪問提案")}]))))

(defn- already-scheduled-violations
  "For `:schedule-service-visit`, refuses to schedule the SAME service
  visit twice, off a dedicated `:scheduled?` fact (never a `:status`
  value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-service-visit)
    (when (store/service-visit-already-scheduled? st subject)
      [{:rule :already-scheduled
        :detail (str subject " は既にスケジュール済み")}])))

(defn- invalid-vector-species-violations
  "For `:vector-survey-log`, if the value declares a `:species-
  observed` outside the closed known set, reject rather than let a
  fabricated species-observation category through. This is a
  FIELD-OBSERVATION category check, never a disease-diagnosis check."
  [{:keys [op]} proposal]
  (when (= op :vector-survey-log)
    (let [species (:species-observed (:value proposal))]
      (when (and (some? species) (not (registry/vector-species-valid? species)))
        [{:rule :invalid-vector-species
          :detail (str species " は既知の vector species カテゴリではない")}]))))

(defn- invalid-vector-count-violations
  "For `:log-environmental-reading`, if the patch declares a
  `:trap-count` that is not a physically plausible reading, reject
  rather than let fabricated/miscounted data through."
  [{:keys [op]} proposal]
  (when (= op :log-environmental-reading)
    (let [count* (:trap-count (:value proposal))]
      (when (and (some? count*) (not (registry/vector-count-valid? count*)))
        [{:rule :invalid-vector-count
          :detail (str count* " は物理的に妥当なトラップ捕獲数の範囲外")}]))))

(defn check
  "Censors a Surveillance Advisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (no-propose-effect-violations request)
                           (unknown-op-violations request)
                           (diagnosis-authority-blocked-violations proposal)
                           (treatment-dispenser-control-blocked-violations proposal)
                           (treatment-actuate-blocked-violations request proposal)
                           (survey-verdict-ungrounded-violations request proposal st)
                           (site-not-verified-violations request proposal st)
                           (trap-unit-not-verified-violations request proposal st)
                           (already-scheduled-violations request st)
                           (invalid-vector-species-violations request proposal)
                           (invalid-vector-count-violations request proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
