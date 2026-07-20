-- The proficiency rank (1..4) a user must reach for a competency node to count as met.
--
-- Node levels are target-level and binary: a node is met or it isn't. Without this column the path
-- projection had nothing to compare a ledger entry against and treated *any* non-zero level as
-- mastery -- so a hire assessed as `beginner` (rank 1), including one who said they knew nothing,
-- was shown as having mastered the node, permanently.
--
-- The default is 2 (intermediate), not 1: `beginner` is exactly what the interviewer records when
-- it has the least evidence to go on, so a bar of 1 would let those placements master a node on the
-- spot -- the bug this column exists to fix. Existing rows take the same bar, which can move a node
-- from MASTERED back to AVAILABLE. That un-earns nothing: the ledger is untouched and monotonic,
-- and node state is *derived* from it at projection time. PMs author real per-node bars via graph
-- editing (backend#50).
ALTER TABLE competencies
    ADD COLUMN IF NOT EXISTS target_level INTEGER NOT NULL DEFAULT 2;

-- One in-progress assessment per user, enforced in the database.
--
-- Starting an assessment used to check for a resumable session *before* the AI call but persist it
-- *after*, so concurrent starts all saw nothing to resume and each created their own -- leaving
-- stranded IN_PROGRESS sessions that a later visit would resume with a question the user never saw.
-- The service now reserves the session first; this index is the backstop.
CREATE UNIQUE INDEX IF NOT EXISTS idx_skill_assessment_one_in_progress_per_user
    ON skill_assessment_sessions (user_id)
    WHERE status = 'IN_PROGRESS';
