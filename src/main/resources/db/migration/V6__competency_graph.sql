-- Phase 0a (backend #13): competency graph + durable progress ledger.
-- Canonical hand-maintained schema kept in sync with the JPA entities Competency,
-- CompetencyEdge and UserCompetencyState. Competencies are referenced by their stable
-- string `key`, never by FK UUID, so progress and edges survive renames and re-seeding.
-- (Issue text said V3; V3-V5 already exist in this fork, so this is V6.)

CREATE TABLE IF NOT EXISTS competencies (
    id          UUID PRIMARY KEY,
    "key"       VARCHAR(255) NOT NULL,
    label       VARCHAR(255) NOT NULL,
    description TEXT,
    kind        VARCHAR(50)  NOT NULL,
    repo_ref    VARCHAR(2048),
    created_at  TIMESTAMP    NOT NULL,

    CONSTRAINT uq_competencies_key UNIQUE ("key"),

    CONSTRAINT chk_competencies_kind
        CHECK (kind IN ('SKILL', 'CONCEPT', 'CONTRIBUTION', 'POLICY', 'CONNECTION', 'CULTURE', 'CHECKPOINT'))
);

CREATE TABLE IF NOT EXISTS competency_edges (
    id       UUID PRIMARY KEY,
    from_key VARCHAR(255)     NOT NULL,
    to_key   VARCHAR(255)     NOT NULL,
    kind     VARCHAR(50)      NOT NULL,
    weight   DOUBLE PRECISION NOT NULL DEFAULT 1.0,

    CONSTRAINT uq_competency_edges_from_to_kind UNIQUE (from_key, to_key, kind),

    CONSTRAINT chk_competency_edges_kind
        CHECK (kind IN ('PREREQUISITE', 'RELATED'))
);

CREATE TABLE IF NOT EXISTS user_competency_state (
    id             UUID PRIMARY KEY,
    user_id        UUID         NOT NULL,
    competency_key VARCHAR(255) NOT NULL,
    level          INTEGER      NOT NULL,
    source         VARCHAR(50)  NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,

    CONSTRAINT uq_user_competency_state_user_competency UNIQUE (user_id, competency_key),

    CONSTRAINT chk_user_competency_state_level
        CHECK (level BETWEEN 0 AND 4),

    CONSTRAINT chk_user_competency_state_source
        CHECK (source IN ('ASSESSED', 'VERIFIED', 'DECLARED'))
);

CREATE INDEX IF NOT EXISTS idx_competency_edges_to_key
    ON competency_edges(to_key);

CREATE INDEX IF NOT EXISTS idx_competency_edges_from_key
    ON competency_edges(from_key);

CREATE INDEX IF NOT EXISTS idx_user_competency_state_user_id
    ON user_competency_state(user_id);
