-- Phase 3 (backend #8): learn-verify modules. Grounded lesson content on onboarding_steps,
-- plus the Verification/VerificationAttempt pair that grades a step's check and, on pass, writes
-- the user_competency_state ledger (source = VERIFIED), unlocking dependents. Canonical
-- hand-maintained schema kept in sync with the JPA entities (no Flyway on the classpath; dev uses
-- ddl-auto: update -- same caveat as V7/V9/V10).

ALTER TABLE onboarding_steps
    ADD COLUMN IF NOT EXISTS content TEXT;

ALTER TABLE onboarding_steps
    ADD COLUMN IF NOT EXISTS lesson_fingerprint VARCHAR(255);

CREATE TABLE IF NOT EXISTS verifications (
    id               UUID PRIMARY KEY,
    step_id          UUID         NOT NULL,
    type             VARCHAR(50)  NOT NULL,
    prompt           TEXT         NOT NULL,
    rubric           TEXT,
    canonical_answer TEXT,
    competency_key   VARCHAR(255) NOT NULL,
    level            VARCHAR(50)  NOT NULL,
    created_at       TIMESTAMP    NOT NULL,

    CONSTRAINT fk_verifications_step
        FOREIGN KEY (step_id) REFERENCES onboarding_steps (id),

    CONSTRAINT uq_verifications_step
        UNIQUE (step_id),

    CONSTRAINT chk_verifications_type
        CHECK (type IN ('KNOWLEDGE', 'EXACT', 'ATTEST'))
);

CREATE TABLE IF NOT EXISTS verification_attempts (
    id               UUID             PRIMARY KEY,
    verification_id  UUID             NOT NULL,
    user_id          UUID             NOT NULL,
    answer           TEXT             NOT NULL,
    passed           BOOLEAN          NOT NULL,
    score            DOUBLE PRECISION NOT NULL,
    feedback         TEXT             NOT NULL,
    hint             TEXT,
    attempt_no       INTEGER          NOT NULL,
    graph_version    INTEGER          NOT NULL,
    created_at       TIMESTAMP        NOT NULL,

    CONSTRAINT fk_verification_attempts_verification
        FOREIGN KEY (verification_id) REFERENCES verifications (id)
);

CREATE INDEX IF NOT EXISTS idx_verification_attempts_verification_user
    ON verification_attempts(verification_id, user_id);
