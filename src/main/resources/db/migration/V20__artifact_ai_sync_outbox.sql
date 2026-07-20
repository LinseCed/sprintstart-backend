-- Per-artifact AI sync state (the outbox behind incremental embedding).
--
-- Existing rows default to SYNCED, not PENDING: they were already sent to the AI index by the old
-- end-of-run batch, and defaulting them to PENDING would re-embed the entire corpus on deploy.
-- The column default flips to PENDING afterwards, so every newly inserted artifact is owed.
ALTER TABLE artifact
    ADD COLUMN IF NOT EXISTS ai_sync_state VARCHAR(20) NOT NULL DEFAULT 'SYNCED';

ALTER TABLE artifact
    ALTER COLUMN ai_sync_state SET DEFAULT 'PENDING';

-- The run that last marked the artifact pending. Distinct from ingestion_run_id, which stays
-- pinned to the run that first created the row -- that is exactly why a run that only *updated*
-- artifacts previously synced none of them.
ALTER TABLE artifact
    ADD COLUMN IF NOT EXISTS ai_sync_run_id UUID;

ALTER TABLE artifact
    ADD COLUMN IF NOT EXISTS ai_sync_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE artifact
    ADD COLUMN IF NOT EXISTS ai_sync_next_attempt_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE artifact
    ADD COLUMN IF NOT EXISTS ai_sync_error TEXT;

ALTER TABLE artifact
    ADD COLUMN IF NOT EXISTS ai_synced_at TIMESTAMP WITH TIME ZONE;

-- The drainer's hot query: pending artifacts whose backoff has elapsed, oldest first.
CREATE INDEX IF NOT EXISTS idx_artifact_ai_sync_pending
    ON artifact (ai_sync_next_attempt_at, ingested_at)
    WHERE ai_sync_state = 'PENDING';

-- Roll-up of a run's sync status.
CREATE INDEX IF NOT EXISTS idx_artifact_ai_sync_run
    ON artifact (ai_sync_run_id, ai_sync_state);
