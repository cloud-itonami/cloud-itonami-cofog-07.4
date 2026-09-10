(ns vectorsurvey.telemetry
  "Sensor-data ingestion + the grounding logic `vectorsurvey.governor`
  uses to enforce this blueprint's own invariant: 'an ELEVATED-RISK/
  NORMAL vector-survey finding must reference measured field data,
  never advisor self-attestation'. Modeled on
  `cloud-itonami-unspsc-27`'s `formation.telemetry` and
  `cloud-itonami-cofog-06.3`'s `leaksurvey.telemetry` -- the same,
  well-tested pattern that makes a condition claim provably traceable
  to measured sensor readings.

  CRITICAL SCOPE NOTE, distinct from both prior siblings: what is being
  grounded here is an ENVIRONMENTAL-CONDITION finding (trap-count
  elevated, standing water present), NEVER a medical/diagnostic
  determination about any person's health. This actor and this
  namespace have NO notion of disease diagnosis, case counts, or
  clinical findings -- `vectorsurvey.governor`'s `diagnosis-authority-
  blocked-violations` independently and permanently blocks any
  proposal that declares a `:diagnosis?`/`:health-determination` field,
  regardless of what this namespace grounds.

  Two responsibilities:

  1. Sensor-reading shape + constructor. A reading is an immutable
     measured fact: what metric, what value, which sensor/trap unit,
     when, for which site. The STORE owns persistence
     (`vectorsurvey.store`); this namespace owns the shape, the
     validation, and the pure grounding logic.

  2. Pure grounding logic. `grounds-verdict?` answers: do the readings
     a vector-survey-log proposal cites as its `:sensor-basis` actually
     cover every sensor-metric an ELEVATED-RISK/NORMAL verdict
     requires? This is the technical kernel of the 'no self-attested
     environmental-condition finding' HARD invariant: an untrusted
     advisor (a real, possibly-hallucinating LLM) must not be able to
     declare a site's vector risk elevated (or normal) from thin air --
     every such verdict must be backed by cited readings whose metrics
     cover BOTH the trap-count and standing-water-presence surface.

  Grounding is REQUIRED for any vector-survey-log verdict that asserts
  a condition (`:elevated-risk` -- 'this site shows an elevated vector
  signal' / `:normal` -- 'this site shows no elevated vector signal').
  A `:needs-more-data` verdict ('I could not determine') is the honest
  'no basis yet' outcome -- it escalates for a human / re-survey and is
  explicitly exempt, because requiring a basis for 'I don't know yet'
  would punish honesty.")

;; A sensor reading is a plain map (NOT a defrecord) so it round-trips
;; through pr-str / edn/read-string unchanged -- a defrecord would emit
;; a tagged literal that edn/read-string cannot read back without a
;; registered reader.

(def required-metrics
  "The fixed environmental-sensing metric surface a vector-survey-log
  ELEVATED-RISK/NORMAL verdict must cite readings covering. Both are
  ENVIRONMENTAL measurements (a trap catch count, a standing-water
  presence flag) -- never a clinical/medical reading of any kind."
  #{:trap-count :standing-water-presence})

(defn reading
  "Construct + validate a sensor reading. `metric` should be one of
  `required-metrics` (or `:species-identification`, an optional third
  metric this domain also tracks but does not require for grounding).
  Throws on the shape errors that would silently corrupt the grounding
  check (missing ids / nil value / non-keyword metric)."
  [{:keys [reading-id site-id metric value unit sensor-id timestamp]
    :or {unit "" timestamp nil}}]
  (when-not (and reading-id (not= reading-id ""))
    (throw (ex-info "sensor reading: reading-id required" {})))
  (when-not (and site-id (not= site-id ""))
    (throw (ex-info "sensor reading: site-id required" {})))
  (when-not (keyword? metric)
    (throw (ex-info (str "sensor reading: metric must be a keyword, got " (pr-str metric)) {})))
  (when (nil? value)
    (throw (ex-info "sensor reading: value required (nil is not a measurement)" {})))
  (when-not (and sensor-id (not= sensor-id ""))
    (throw (ex-info "sensor reading: sensor-id required" {})))
  {:type :sensor-reading :reading-id reading-id :site-id site-id :metric metric
   :value value :unit (or unit "") :sensor-id sensor-id :timestamp timestamp})

(defn readings-by-id
  "Index a seq of readings by their reading-id (last wins on collision)."
  [readings]
  (into {} (map (juxt :reading-id identity)) readings))

(defn grounds-verdict?
  "Does `cited-ids` (the reading-ids a vector-survey-log proposal
  claims as its `:sensor-basis`) actually ground an ELEVATED-RISK/
  NORMAL verdict for `site-id`?

   - Every cited id must resolve to a REAL reading for `site-id` (a
     cited id that points at another site's reading, or at nothing,
     grounds nothing -- it is the advisor naming evidence it does not
     have).
   - Every cited id must be DISTINCT (citing the same reading twice
     does not widen coverage).
   - The union of the resolved readings' metrics must cover EVERY
     metric in `required-metrics`. Partial coverage is not grounding:
     'I counted the trap catch but did not check for standing water'
     does not substantiate an elevated-risk finding.

  Returns true only on full coverage; no cited ids -> false (a verdict
  with no basis at all can never be grounded through this gate)."
  [site-id cited-ids readings]
  (let [by-id (readings-by-id readings)
        ids (seq cited-ids)
        resolved (keep (fn [rid]
                          (let [r (get by-id rid)]
                            (when (and r (= (:site-id r) site-id)) r)))
                        ids)]
    (boolean
     (and ids
          (= (count resolved) (count (distinct ids)))
          (every? #(contains? (set (map :metric resolved)) %) required-metrics)))))
