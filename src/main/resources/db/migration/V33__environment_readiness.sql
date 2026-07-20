-- Environment readiness (Onboarding v2, slice 2).
--
-- Roughly three days of week one go into configuring a local environment, against a median
-- time-to-first-commit measured in weeks. Nothing in the product touched that gap before this.
--
-- Readiness is settled by evidence, never a checkbox: "I set up my environment" is exactly what a
-- stuck person ticks before asking for help. This table holds only evidence the hire actively
-- reported (a build-and-test run from their machine, a green CI run). Readiness that can be derived
-- from what the system already ingested -- a pull request they authored -- is computed on read and
-- never written here, so there is nothing to backfill.
CREATE TABLE IF NOT EXISTS environment_readiness
(
    id             UUID PRIMARY KEY,
    hire_id        UUID        NOT NULL,
    project_id     UUID        NOT NULL,
    -- The moment the evidence describes, not when the row was written.
    ready_at       TIMESTAMPTZ NOT NULL,
    -- BUILD_TEST_RUN | GREEN_CI (PULL_REQUEST is derived-only and never stored here).
    evidence       VARCHAR(32) NOT NULL,
    -- A pointer to the evidence -- a CI URL, a one-line build summary. Never required.
    evidence_detail TEXT,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- One readiness fact per hire per project. Readiness, once achieved, does not change; the first
-- evidence wins and later reports are no-ops.
CREATE UNIQUE INDEX IF NOT EXISTS uq_environment_readiness_hire_project
    ON environment_readiness (hire_id, project_id);
