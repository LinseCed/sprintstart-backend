-- Onboarding instrumentation (Onboarding v2, slice 0).
--
-- The initiative has run since Phase 0 without any way to answer "how long is onboarding actually
-- taking". The metrics are derived on read rather than written to an event log -- every fact
-- already lives somewhere durable, and a second copy would drift -- so this migration only adds
-- the columns that were missing from those durable rows.

-- When a pull request was merged, and when anyone other than its author first responded to it.
--
-- Both were already being fetched from GitHub and then dropped at the mapper. Time-to-first-merged
-- -PR is the north-star metric and "receiving a response" is among the most-evidenced newcomer
-- barriers, so these two columns are what the whole measurement rests on.
--
-- Neither is part of what the AI service embeds, so writing them never marks an artifact for
-- re-indexing. Existing rows stay null until the next crawl refreshes them.
ALTER TABLE artifact
    ADD COLUMN IF NOT EXISTS merged_at_source        TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS first_response_at_source TIMESTAMPTZ;

-- Attribution + type is how a hire's pull requests are found; the index matches that access path.
CREATE INDEX IF NOT EXISTS idx_artifact_author_type
    ON artifact (author_login, artifact_type)
    WHERE author_login IS NOT NULL;

-- When somebody joined a project: the moment onboarding's clock starts.
--
-- Deliberately left NULL for existing assignments rather than backfilled with NOW() or with the
-- user's creation date. A fabricated join date would put invented numbers underneath the metric the
-- whole initiative is judged on; a null is reported as "clock unknown" and excluded from medians.
ALTER TABLE user_projects
    ADD COLUMN IF NOT EXISTS assigned_at TIMESTAMPTZ;
