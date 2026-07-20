-- The ramp and the autonomy exit (Onboarding v2, slice 4).
--
-- Onboarding is a ramp of real tasks, not a course ending in one. Almost all of the ramp is derived
-- on read -- which stage, which task, what unlocked it -- because every underlying fact already
-- lives somewhere durable. Only two things had to be stored.

-- 1. Whether a pull request was sent back.
--
-- "Done with no review rework" is half the operational definition of autonomy, and it cannot be
-- recovered from merge state: a pull request that went through three rounds of requested changes
-- merges exactly like one that did not. Existing rows default to 0 -- which is a real claim about
-- them, so a hire whose earlier work predates this column can reach autonomy on it. The alternative
-- (NULL = unknown, blocking autonomy) would punish people for when the column shipped; the crawl
-- backfills real counts on the next pass either way.
ALTER TABLE artifacts
    ADD COLUMN IF NOT EXISTS changes_requested_count INTEGER NOT NULL DEFAULT 0;

-- 2. The moment autonomy was reached.
--
-- Recomputing "is autonomous now" gives a boolean, and a boolean cannot be announced. The end of
-- onboarding should be a dated event somebody can point at -- for the hire and for their PM --
-- rather than a percentage that quietly crosses a line.
--
-- Written once and never updated: if later work needs more help, that is ordinary, and it does not
-- un-happen the day somebody first shipped a change with no help and no rework. Same reasoning that
-- keeps the competency ledger monotonic.
CREATE TABLE IF NOT EXISTS autonomy_milestones
(
    id                     UUID PRIMARY KEY,
    hire_id                UUID        NOT NULL,
    project_id             UUID        NOT NULL,
    reached_at             TIMESTAMPTZ NOT NULL,
    proven_by_artifact_id  UUID
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_autonomy_milestones_hire_project
    ON autonomy_milestones (hire_id, project_id);
