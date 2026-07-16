-- Phase 2 slice 3 (backend #7): change history, automatic classification, per-user graph pins,
-- and the request-driven session-boundary stand-in this backend never had (no Flyway on the
-- classpath -- dev uses ddl-auto: update; this documents the canonical schema delta, same caveat
-- as V7/V9).

ALTER TABLE competencies
    ADD COLUMN IF NOT EXISTS invariant BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE sprintstart_users
    ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP;

-- competency_graph_version becomes append-only (one row per version, like blueprints) instead of
-- a single row mutated in place. Any pre-existing single row predates classification and is reset
-- to a version-1 ADDITIVE baseline rather than guessed at retroactively.
ALTER TABLE competency_graph_version
    ADD COLUMN IF NOT EXISTS classification VARCHAR(50);

UPDATE competency_graph_version SET version = 1, classification = 'ADDITIVE' WHERE classification IS NULL;

ALTER TABLE competency_graph_version
    ALTER COLUMN classification SET NOT NULL;

ALTER TABLE competency_graph_version
    RENAME COLUMN updated_at TO created_at;

ALTER TABLE competency_graph_version
    DROP CONSTRAINT IF EXISTS uq_competency_graph_version_version;

ALTER TABLE competency_graph_version
    ADD CONSTRAINT uq_competency_graph_version_version UNIQUE (version);

ALTER TABLE competency_graph_version
    DROP CONSTRAINT IF EXISTS chk_competency_graph_version_classification;

ALTER TABLE competency_graph_version
    ADD CONSTRAINT chk_competency_graph_version_classification
        CHECK (classification IN ('INVARIANT', 'ADDITIVE', 'STRUCTURAL'));

CREATE TABLE IF NOT EXISTS competency_graph_changes (
    id             UUID PRIMARY KEY,
    version        INTEGER      NOT NULL,
    change_type    VARCHAR(50)  NOT NULL,
    competency_key VARCHAR(255),
    from_key       VARCHAR(255),
    to_key         VARCHAR(255),
    edge_kind      VARCHAR(50),
    created_at     TIMESTAMP    NOT NULL,

    CONSTRAINT chk_competency_graph_changes_type
        CHECK (change_type IN (
            'NODE_ADDED', 'NODE_REMOVED', 'NODE_MODIFIED', 'EDGE_ADDED', 'EDGE_REMOVED', 'EDGE_MODIFIED'
        ))
);

CREATE INDEX IF NOT EXISTS idx_competency_graph_changes_version
    ON competency_graph_changes(version);

-- NOTE: competencies/edges seeded before this migration have no corresponding change-history row,
-- so EffectiveGraphResolver will not consider them visible until one exists. Not backfilled here
-- deliberately -- this only affects dev-seeded data (the only real writer today), and the
-- straightforward local remedy is re-seeding rather than a synthesized backfill in SQL whose
-- execution against a real schema is already unconfirmed (see V7's ddl-auto note).

CREATE TABLE IF NOT EXISTS user_graph_pins (
    id             UUID      PRIMARY KEY,
    user_id        UUID      NOT NULL,
    pinned_version INTEGER   NOT NULL,
    updated_at     TIMESTAMP NOT NULL,

    CONSTRAINT uq_user_graph_pins_user_id UNIQUE (user_id)
);
