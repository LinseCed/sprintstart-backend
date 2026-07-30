-- Two protections that have to exist *before* generation runs on ingestion (S3), because turning
-- auto-generation on without them silently destroys work.
--
-- 1. PROVENANCE protects edits. `competencies.provenance` mirrors `module_pages` -- any write by a
--    person marks the row PM, and regeneration must skip a PM row. Without it, "generation runs on
--    ingestion and an admin can correct it" means "generation overwrites the admin", with no trace
--    that a correction was ever made.
--
--    Defaults to PM, which is accurate (every writer today is a person -- generation has had no
--    caller since the proposal queue went with the graph) and is the safe direction to be wrong in:
--    if S3's persister forgets to mark generated rows AI, a row merely never improves, instead of
--    somebody's correction being discarded.
--
-- 2. TOMBSTONES protect deletions. Dedup matches on exact key *and* embedding similarity, so a
--    competency a PM removes comes back next crawl under a rephrasing, and they remove it again,
--    forever. `competency_tombstones` remembers the removal and the generator is given them as
--    exclusions -- a tombstone the generator never sees is not a tombstone. This is the board's
--    rule applied to the generator: a dismissal is sticky and binds the mentor too.
--
--    The label is stored, not just the key, because the point is to block a *rephrasing*: the key
--    check alone would miss it, and the label is what the similarity check embeds.
--
-- DEVIATION from the design of record, which says a deleted competency "keeps its row, marked
-- deleted". That would require all eight existing readers -- studio, dashboard, module authoring,
-- verification, ramp, starter-work matching -- to filter the flag, and any reader added later that
-- forgets it produces a competency that is deleted but still visible. That is the ghost-row failure
-- the graph-visibility replay already has. A separate table gives the same property with no reader
-- able to forget it, and leaves removal the real delete S0 made it.
ALTER TABLE competencies
    ADD COLUMN IF NOT EXISTS provenance VARCHAR(16) NOT NULL DEFAULT 'PM';

CREATE TABLE IF NOT EXISTS competency_tombstones (
    id         UUID         PRIMARY KEY,
    "key"      VARCHAR(255) NOT NULL UNIQUE,
    label      VARCHAR(255) NOT NULL,
    deleted_at TIMESTAMP    NOT NULL
);
