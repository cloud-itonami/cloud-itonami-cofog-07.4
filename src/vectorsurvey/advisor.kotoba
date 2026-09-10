(ns vectorsurvey.advisor
  "Surveillance Advisor -- the *contained intelligence node* for the
  community vector-control / environmental-health monitoring actor.

  **THIS ADVISOR IS NOT A MEDICAL OR DIAGNOSTIC AUTHORITY.** It only
  drafts ENVIRONMENTAL survey findings (trap catch counts, standing-
  water/breeding-site presence, observed vector-species category) --
  it never drafts a disease diagnosis, a case classification, or any
  clinical/medical determination about any person. Every output is
  censored downstream by `vectorsurvey.governor`, which independently
  and permanently blocks any proposal declaring a
  `:diagnosis?`/`:health-determination` field, regardless of what this
  advisor drafts.

  It normalizes environmental-reading patches (trap-count/standing-
  water-detected?), drafts a vector-survey finding GROUNDED in cited
  sensor readings (`vectorsurvey.telemetry`), drafts a trap
  service-visit scheduling proposal against a site and trap unit, and
  drafts an outbreak-risk-signal escalation flag. CRITICAL: it is a
  smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record and NEVER
  a real treatment/pesticide-dispenser actuation or any diagnostic/
  outbreak determination -- see README `What this actor does NOT do`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- informational only, NOT trusted
                                 ; by the governor for any ground-truth
                                 ; check (see `vectorsurvey.governor`)
     :cites      [kw|str ..]    ; fields the advisor used
     :effect     kw             ; how a commit would mutate the SSoT --
                                 ; ALWAYS one of the closed
                                 ; #{:site/upsert :survey/log-set
                                 ; :service-visit/schedule
                                 ; :outbreak-signal/escalate}
                                 ; propose-shaped effects, NEVER a
                                 ; direct treatment-dispenser-control
                                 ; effect
     :stake      kw|nil         ; :vector/outbreak-risk-signal | nil
     :confidence 0..1}

  CRITICAL invariant this advisor upholds: every request it is asked to
  route MUST itself carry `:effect :propose` (the request-level
  contract every caller of this actor agrees to) -- `vectorsurvey.
  governor` HARD-holds any request that doesn't, so a mis-wired caller
  can never reach a commit path even if this advisor were compromised."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [kotoba.lang.text :as str]
            [vectorsurvey.registry :as registry]
            [vectorsurvey.store :as store]
            [vectorsurvey.telemetry :as telemetry]
            [langchain.model :as model]))

(defn- log-environmental-reading
  "Site environmental-reading intake upsert -- the advisor only
  normalizes/validates the patch; it does not invent the site's
  trap-count, standing-water status or verification status. High
  confidence, low stakes -- administrative logging, not an operational
  decision, and NEVER a clinical/health reading of any kind."
  [_db {:keys [patch]}]
  {:summary    (str "サイト環境観測記録更新: " (pr-str (keys patch)))
   :rationale  "入力patchの正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :site/upsert
   :value      patch
   :stake      nil
   :confidence 0.95})

(defn- vector-survey-log
  "Draft a vector-survey finding for a site, GROUNDED in cited sensor
  readings when the verdict asserts a condition (`:elevated-risk`/
  `:normal`). The advisor reports whether it believes its own cited
  basis grounds the verdict, but `vectorsurvey.governor` NEVER trusts
  this report -- it independently re-derives grounding from the site's
  own stored readings via `vectorsurvey.telemetry/grounds-verdict?`
  before any commit is possible. A `:needs-more-data` verdict needs no
  basis -- the honest 'I could not determine' outcome. This is an
  ENVIRONMENTAL finding only -- never a disease diagnosis or case
  classification."
  [db {:keys [subject value]}]
  (let [site-id (:site-id value)
        verdict (:verdict value)
        cited (:sensor-basis value)
        readings (store/readings-for-site db site-id)
        grounded? (or (= verdict :needs-more-data)
                      (telemetry/grounds-verdict? site-id cited readings))]
    {:summary    (str subject " 向け環境調査結果 (" (name (or verdict :unknown)) ")"
                      (when site-id (str " site=" site-id)))
     :rationale  (str "verdict=" verdict " sensor-basis=" (pr-str cited)
                      " grounded?=" grounded?)
     :cites      (vec cited)
     :effect     :survey/log-set
     :value      value
     :stake      nil
     :confidence (if grounded? 0.85 0.25)}))

(defn- schedule-service-visit
  "Draft a trap service-visit scheduling proposal against a site and
  trap-servicing unit. The advisor reports what it can see (site/trap
  verified?/registered?) in its rationale, but `vectorsurvey.governor`
  NEVER trusts this report -- it independently re-derives
  verified?/registered? from the site's and trap unit's own stored
  fields before any commit is possible."
  [db {:keys [subject value]}]
  (let [site-id (:site-id value)
        trap-id (:trap-id value)
        s (store/site db site-id)
        u (store/trap-unit db trap-id)
        ready? (and s u (registry/site-ready? s) (registry/trap-unit-ready? u))]
    {:summary    (str subject " 向けトラップ保守訪問提案"
                      (when s (str " site=" site-id))
                      (when u (str " trap=" trap-id)))
     :rationale  (str "site-verified?=" (some-> s registry/site-verified?)
                      " site-registered?=" (some-> s registry/site-registered?)
                      " trap-verified?=" (some-> u registry/trap-unit-verified?)
                      " trap-registered?=" (some-> u registry/trap-unit-registered?)
                      " actuate-treatment?=" (boolean (:actuate-treatment? value)))
     :cites      (cond-> [] s (conj site-id) u (conj trap-id))
     :effect     :service-visit/schedule
     :value      value
     :stake      nil
     :confidence (if (and ready? (not (:actuate-treatment? value))) 0.9 0.3)}))

(defn- escalate-outbreak-signal
  "Draft an elevated-vector-risk signal for human public-health-
  official review. ALWAYS `:stake :vector/outbreak-risk-signal` -- an
  outbreak-risk signal is NEVER a proposal the advisor may quietly
  downgrade to low-stakes, and it is never gated on the referenced
  site being verified (a concern can be raised about ANY site, verified
  or not -- see README `What this actor does NOT do` re: never
  blocking safety-relevant reporting on an administrative technicality).
  CRITICAL: this is an ENVIRONMENTAL/statistical signal, NEVER a
  disease diagnosis or outbreak declaration -- only a licensed
  public-health official may interpret and act on it. See
  `vectorsurvey.phase`: no phase ever adds this op to a phase's
  `:auto` set; `vectorsurvey.governor` also always escalates on
  `:vector/outbreak-risk-signal`. Two independent layers agree,
  deliberately."
  [db {:keys [subject value]}]
  (let [site-id (:site-id value)
        s (and site-id (store/site db site-id))]
    {:summary    (str subject " 向け媒介動物リスク上昇シグナル報告 (" (:severity value) ")"
                      (when s (str " site=" site-id)))
     :rationale  (str "severity=" (:severity value) " description=" (:description value))
     :cites      (if s [site-id] [])
     :effect     :outbreak-signal/escalate
     :value      value
     :stake      :vector/outbreak-risk-signal
     :confidence 0.9}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :effect :propose :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-environmental-reading   (log-environmental-reading db request)
    :vector-survey-log           (vector-survey-log db request)
    :schedule-service-visit      (schedule-service-visit db request)
    :escalate-outbreak-signal    (escalate-outbreak-signal db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは地域媒介動物対策・環境衛生モニタリングアドバイザーの"
       "助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:site/upsert|:survey/log-set|"
       ":service-visit/schedule|:outbreak-signal/escalate) "
       ":stake(:vector/outbreak-risk-signal か nil) :confidence(0..1)。\n"
       "重要: あなたは医療・診断の権限を一切持ちません。疾病の診断や"
       "アウトブレイク宣言を絶対に提案してはいけません -- 観測された"
       "環境条件(トラップ捕獲数、滞留水の有無、種別カテゴリ)の報告のみ"
       "行います。ELEVATED-RISK/NORMAL判定には必ず実測センサー読み取り"
       "値の引用が必要で、引用の無い判定を提案してはいけません。"
       "未検証または未登録のサイト・トラップユニットに対する訪問提案を"
       "してはいけません。処理・薬剤散布装置の直接操作(actuate)を絶対に"
       "提案してはいけません(この actor は提案のみを行い、実行は一切"
       "行いません)。"))

(defn- facts-for [st {:keys [op subject value]}]
  (case op
    :log-environmental-reading   {:site (store/site st subject)}
    :vector-survey-log           {:site (store/site st (:site-id value))
                                   :readings (store/readings-for-site st (:site-id value))}
    :schedule-service-visit      {:site (store/site st (:site-id value))
                                   :trap-unit (store/trap-unit st (:trap-id value))}
    :escalate-outbreak-signal    {:site (and (:site-id value) (store/site st (:site-id value)))}
    {}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so `vectorsurvey.governor`
  escalates/holds -- an LLM hiccup can never auto-schedule a service
  visit, auto-file a survey finding, or auto-escalate/downgrade an
  outbreak-risk signal."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :surveillance-advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
