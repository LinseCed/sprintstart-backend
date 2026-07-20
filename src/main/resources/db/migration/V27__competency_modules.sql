-- Modules become competency-owned, shared artifacts (backend#49).
--
-- Content used to live on onboarding_steps, which hang off a per-user onboarding_paths row. So
-- there was no "the module for competency X" -- only N independently AI-generated private copies,
-- one per hire. A PM editing a node's content edited one person's copy; two hires learning the same
-- thing got different material; the content-quality loop improved a single hire's lesson. This
-- makes the module the shared artifact, keyed by (competency, project), while per-user *progress*
-- stays exactly where it already was and is already correct: user_competency_state.
--
-- The per-user content tree itself is retired separately (backend#53) so this stays reviewable.
--
-- NOTE: like the other onboarding tables these are built by Hibernate ddl-auto in dev; this
-- migration documents the delta for prod. Kept in sync with the JPA entities CompetencyModule /
-- ModulePage / Verification.

CREATE TABLE IF NOT EXISTS competency_modules (
    id                 UUID PRIMARY KEY,
    -- The competency taught, by stable key -- never by id, matching the ledger and graph edges, so
    -- a module survives a rename or re-seed.
    competency_key     VARCHAR(255) NOT NULL,
    -- Not nullable: content that claims no project is content grounded in nothing. The competency
    -- stays global, so "earn once, transfers across projects" is preserved -- the ledger records
    -- the competency, not the module a hire happened to learn it from.
    project_id         UUID         NOT NULL,
    version            INTEGER      NOT NULL,
    status             VARCHAR(32)  NOT NULL,
    origin             VARCHAR(32)  NOT NULL DEFAULT 'PM',
    title              VARCHAR(255) NOT NULL,
    summary            TEXT,
    corpus_fingerprint VARCHAR(255),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_competency_modules_key_project_version UNIQUE (competency_key, project_id, version)
);

-- At most one live module per (competency, project). Enforced in the database as well as in
-- CompetencyModuleService.approve, because two live versions would make "what does this node
-- teach" ambiguous and the path would pick one arbitrarily.
CREATE UNIQUE INDEX IF NOT EXISTS uq_competency_modules_active
    ON competency_modules (competency_key, project_id)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_competency_modules_project_status
    ON competency_modules (project_id, status);

CREATE TABLE IF NOT EXISTS module_pages (
    id         UUID PRIMARY KEY,
    module_id  UUID         NOT NULL REFERENCES competency_modules (id) ON DELETE CASCADE,
    kind       VARCHAR(32)  NOT NULL,
    title      VARCHAR(255) NOT NULL,
    body       TEXT,
    position   INTEGER      NOT NULL,
    -- Load-bearing for re-synthesis: an AI pass may replace what it wrote, and must leave PM pages
    -- alone. Without it, regenerating a module silently discards a human's edits.
    provenance VARCHAR(32)  NOT NULL DEFAULT 'PM',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_module_pages_module ON module_pages (module_id, position);

-- A check is now owned by a module. step_id becomes nullable and stays only for the legacy
-- per-user checks, which go with the per-user tree (backend#53).
--
-- Deliberately one table with two possible owners rather than a new module_verifications table:
-- verification_attempts point at a verification, so a hire's attempt history needs no migration
-- and no repointing -- the rows it references simply change owner over time.
ALTER TABLE verifications
    ADD COLUMN IF NOT EXISTS module_id UUID REFERENCES competency_modules (id) ON DELETE CASCADE;

ALTER TABLE verifications
    ALTER COLUMN step_id DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_verifications_module
    ON verifications (module_id)
    WHERE module_id IS NOT NULL;

-- Exactly one owner. A check owned by neither is unreachable; one owned by both would be gradeable
-- through two routes with different access rules.
ALTER TABLE verifications
    DROP CONSTRAINT IF EXISTS ck_verifications_single_owner;

ALTER TABLE verifications
    ADD CONSTRAINT ck_verifications_single_owner
        CHECK ((step_id IS NULL) <> (module_id IS NULL));
