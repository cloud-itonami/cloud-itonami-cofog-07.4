(ns vectorsurvey.phase
  "Phase 0->3 staged rollout for the community vector-control /
  environmental-health monitoring Surveillance Advisor actor.

    Phase 0  read-only          -- no writes, still governor-gated.
    Phase 1  assisted-intake    -- environmental-reading logging
                                    allowed, every write needs human
                                    approval.
    Phase 2  assisted-survey    -- adds vector-survey-log filing,
                                    still approval.
    Phase 3  supervised-auto    -- adds service-visit scheduling and
                                    outbreak-signal escalation (still
                                    always approval -- see below);
                                    governor-clean, high-confidence
                                    `:log-environmental-reading` (no
                                    physical/financial risk, and never
                                    a clinical claim) may auto-commit.

  `:schedule-service-visit` is deliberately ABSENT from every phase's
  `:auto` set, including phase 3 -- a permanent structural fact, not a
  rollout milestone still to come. Servicing a trap or treating a
  breeding site is the one act in this domain with physical consequence
  (a robot is actually dispatched and a site is actually touched); it
  is always a human survey coordinator's call.
  `vectorsurvey.governor`'s `treatment-actuate-blocked-violations`
  HARD-blocks actuate attempts unconditionally, and the confidence/
  high-stakes gate independently never lets `:escalate-outbreak-
  signal` auto-commit either -- multiple independent layers agree on
  where this actor's authority ends. Like every prior sibling's
  phase-3 `:auto` set, this domain has only ONE member
  (`:log-environmental-reading`) -- no separate no-risk lifecycle
  distinct from ordinary record logging.

  NOTE what is deliberately absent from this whole phase table: there
  is no op, at any phase, that ever asserts a disease diagnosis or
  outbreak declaration -- `vectorsurvey.governor`'s `diagnosis-
  authority-blocked-violations` is unconditional regardless of phase,
  the same 'a rollout milestone cannot ever reach this' posture
  `motomfg.phase`'s own certification-authority boundary establishes.")

(def write-ops
  #{:log-environmental-reading :vector-survey-log
    :schedule-service-visit :escalate-outbreak-signal})

;; NOTE the invariant: `:schedule-service-visit` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member of
;; any phase's `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                            :auto #{}}
   1 {:label "assisted-intake" :writes #{:log-environmental-reading}                  :auto #{}}
   2 {:label "assisted-survey" :writes #{:log-environmental-reading :vector-survey-log} :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:log-environmental-reading}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:schedule-service-visit` is never auto-eligible at any phase, so
    it always escalates once the governor clears it (or holds if the
    governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Public Health Governor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
