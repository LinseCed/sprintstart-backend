-- Persists the concrete source instance a run belongs to on the ingestion run itself.
--
-- Runs are created from GitHub connection/update transactions that already carry owner and name;
-- these columns store that identity (plus the resolved repository connection id) so the frontend
-- can map a run to a specific repository instead of only to the source system.
--
-- All columns are nullable: non-repository sources (for example UPLOAD) have no repository, and
-- runs created before this migration have no metadata to backfill. As with the rest of the schema,
-- Hibernate's ddl-auto already emits these columns; this migration is idempotent so it stays a
-- no-op against such a schema and exists so the change is recorded in the migration history.

ALTER TABLE IF EXISTS ingestion_run
    ADD COLUMN IF NOT EXISTS repository_id UUID;

ALTER TABLE IF EXISTS ingestion_run
    ADD COLUMN IF NOT EXISTS owner VARCHAR(255);

ALTER TABLE IF EXISTS ingestion_run
    ADD COLUMN IF NOT EXISTS name VARCHAR(255);
