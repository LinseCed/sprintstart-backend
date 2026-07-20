-- The baseline becomes a competency selection, not a step list (backend#52).
--
-- A blueprint used to hold prose *steps* -- a second content model running in parallel to the
-- competency graph, whose only structural job was to carry a competency_key so path targeting knew
-- what to aim at (the "blueprint->target bridge" of V19/#39). The baseline's real job is the one
-- the design gave it: which competencies must everyone in a scope reach, and to what level. That
-- is a selection over the graph, so the selection is what is stored.
--
-- NOTE: like the other onboarding tables, blueprints/blueprint_steps are built by Hibernate
-- ddl-auto in dev; this migration documents the delta for prod. How prod applies migrations is
-- tracker #12 open question 4. Kept in sync with the JPA entities Blueprint / BlueprintCompetency.

CREATE TABLE IF NOT EXISTS blueprint_competencies (
    id               UUID PRIMARY KEY,
    blueprint_id     UUID         NOT NULL REFERENCES blueprints (id) ON DELETE CASCADE,
    competency_key   VARCHAR(255) NOT NULL,
    -- NULL means "use the competency's own target_level" (V25). An override exists because the bar
    -- is partly a property of this team's expectations, not only of the competency.
    target_level     INTEGER,
    requirement      VARCHAR(32)  NOT NULL DEFAULT 'RECOMMENDED',
    invariant        BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Why the proposer selected this competency. Review-facing only.
    rationale        TEXT,
    position         INTEGER      NOT NULL,
    status           VARCHAR(32)  NOT NULL DEFAULT 'PROPOSED',
    decided_at       TIMESTAMPTZ,
    rejection_reason TEXT
);

CREATE INDEX IF NOT EXISTS idx_blueprint_competencies_blueprint
    ON blueprint_competencies (blueprint_id);

-- One competency is selected at most once per baseline: two entries for the same key would give a
-- node two different bars in one scope with no rule for which wins.
CREATE UNIQUE INDEX IF NOT EXISTS uq_blueprint_competencies_key
    ON blueprint_competencies (blueprint_id, competency_key);

-- Backfill: the surviving content of a step was always its competency_key. Steps that never got
-- one (the AI only started emitting keys in #41, and PMs could leave them unset) carry nothing a
-- selection can be made of -- there is no node they point at -- so they are dropped rather than
-- guessed at. A PM regenerates the baseline to get a real selection; nothing durable is lost,
-- since progress lives on the competency ledger, never on a blueprint.
INSERT INTO blueprint_competencies (
    id, blueprint_id, competency_key, target_level, requirement, invariant, rationale, position,
    status, decided_at, rejection_reason
)
SELECT DISTINCT ON (s.blueprint_id, s.competency_key)
       gen_random_uuid(),
       s.blueprint_id,
       s.competency_key,
       NULL,
       UPPER(COALESCE(s.requirement, 'recommended')),
       COALESCE(s.invariant, FALSE),
       s.description,
       s.position,
       COALESCE(s.status, 'PROPOSED'),
       s.decided_at,
       s.rejection_reason
FROM blueprint_steps s
WHERE s.competency_key IS NOT NULL
ORDER BY s.blueprint_id, s.competency_key, s.position;

DROP TABLE IF EXISTS blueprint_steps;
