-- Consent for using a user's existing work in their projects' connected repositories to calibrate
-- their skill assessment. Null means no consent -- the default, and what revoking returns it to.
ALTER TABLE sprintstart_users
    ADD COLUMN IF NOT EXISTS github_seeding_consent_at TIMESTAMP WITH TIME ZONE;

-- The derived prior: counted buckets only, never the underlying activity. Deleted outright when
-- consent is withdrawn, so revocation removes the data rather than merely hiding it.
CREATE TABLE IF NOT EXISTS github_history_priors (
    user_id     UUID PRIMARY KEY,
    computed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS github_history_prior_signals (
    user_id      UUID    NOT NULL REFERENCES github_history_priors (user_id) ON DELETE CASCADE,
    signal_key   VARCHAR(255) NOT NULL,
    signal_count INTEGER NOT NULL,
    PRIMARY KEY (user_id, signal_key)
);
