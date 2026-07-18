-- Backend #9: artifact verification. Adds VerificationType.ARTIFACT -- a hire submits a PR
-- number, the backend gathers its real GitHub state (title, body, state, changed files, CI
-- status, commit messages via the connectors.github module) and delegates rubric judgment to the
-- AI service's already-built `type: "artifact"` grader. repository_connection_id is set by the PM
-- at authoring time (required only for ARTIFACT, like rubric/canonical_answer are for their own
-- types) and references gh_repository_connections by plain id -- no FK, matching this module's
-- existing loosely-coupled cross-module convention. Canonical hand-maintained schema delta; same
-- ddl-auto caveat as prior migrations.

ALTER TABLE verifications
    ADD COLUMN IF NOT EXISTS repository_connection_id UUID;

ALTER TABLE verifications
    DROP CONSTRAINT IF EXISTS chk_verifications_type;

ALTER TABLE verifications
    ADD CONSTRAINT chk_verifications_type
        CHECK (type IN ('KNOWLEDGE', 'EXACT', 'ATTEST', 'ARTIFACT'));
