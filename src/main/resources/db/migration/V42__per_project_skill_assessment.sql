-- The skill assessment becomes per-project: a hire already placed for one project must still sit
-- the interview for a second, since the questions worth asking are scoped to what *that* project's
-- modules actually teach. The old global, one-time session model had no way to represent "assessed
-- for project A, not yet for project B".
--
-- Existing sessions/turns cannot be honestly attributed to a project (the column never existed),
-- and the placement any of them already produced is durable in user_competency_state, which this
-- migration does not touch -- so nothing earned is lost by clearing the transient interview
-- transcripts.
DELETE FROM skill_assessment_turns;
DELETE FROM skill_assessment_sessions;

-- Superseded by the per-(user, project) index below.
DROP INDEX IF EXISTS idx_skill_assessment_one_in_progress_per_user;

ALTER TABLE skill_assessment_sessions
    ADD COLUMN IF NOT EXISTS project_id UUID;

ALTER TABLE skill_assessment_sessions
    ALTER COLUMN project_id SET NOT NULL;

-- One in-progress assessment per (user, project) -- the same hire now runs the interview once per
-- project they are on, not once ever.
CREATE UNIQUE INDEX IF NOT EXISTS idx_skill_assessment_one_in_progress_per_user_project
    ON skill_assessment_sessions (user_id, project_id)
    WHERE status = 'IN_PROGRESS';

CREATE INDEX IF NOT EXISTS idx_skill_assessment_sessions_user_project
    ON skill_assessment_sessions (user_id, project_id);
