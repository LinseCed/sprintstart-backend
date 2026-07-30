-- Onboarding setup collapses into "connect a repository": a crawl reaching the AI index generates
-- the competency vocabulary and the modules behind it, with nobody approving anything. The gate is
-- grounding -- a competency that cannot cite a chunk never arrives -- not a click.
--
-- This is safe only because S2 landed first. Regeneration skips any competency a person has touched
-- (`competencies.provenance = 'PM'`) and refuses to resurrect anything in `competency_tombstones`.
-- Turning generation on before those existed would have silently discarded corrections and
-- re-proposed what somebody deleted, every crawl.
--
-- The single row here is the corpus fingerprint of the last completed run. The AI service is
-- stateless: it short-circuits as `unchanged` when the fingerprint it computes matches the one sent,
-- and this is the only place that can remember it. Without it every crawl pays for a generation to
-- be told nothing is new.
--
-- One row, not one per project: competencies are global ("earn once, transfers") and the generator
-- retrieves over the whole corpus. The module pass is deliberately *not* guarded by this -- it has
-- its own per-(competency, project) fingerprint, and its real guard is "this competency has no
-- module yet", so a competency left uncovered by an earlier run is still picked up when the corpus
-- has not moved since.
CREATE TABLE IF NOT EXISTS vocabulary_generation_state (
    id               UUID         PRIMARY KEY,
    last_fingerprint VARCHAR(255),
    last_run_at      TIMESTAMP    NOT NULL
);
