-- Initiative E · goal-directed path.
--
-- The contribution a hire has claimed as their path's destination, per project. Stored rather
-- than derived because hire->task matching is an AI call: deriving it would put a model round
-- trip on every path read, and would let a hire's destination move between two page loads.
--
-- competency_key points at the CONTRIBUTION node rather than at the proposal, matching how the
-- rest of the system keys off the durable competency key. source_proposal_id is kept only so a
-- client can show the underlying task without re-deriving which proposal minted the node; it is
-- nullable so a goal survives its proposal row being cleaned up.

CREATE TABLE IF NOT EXISTS user_goals
(
    id                 UUID PRIMARY KEY,
    user_id            UUID        NOT NULL,
    project_id         UUID        NOT NULL,
    competency_key     TEXT        NOT NULL,
    source_proposal_id UUID,
    claimed_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Onboarding is per-project, so a hire aims at one contribution per project they're onboarding
-- onto -- not one globally.
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_goals_user_project
    ON user_goals (user_id, project_id);
