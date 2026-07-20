-- Task 0 (Onboarding v2, slice 2).
--
-- The trivial first task a new hire walks the branch -> PR -> review -> merge loop on while the
-- stakes are nil. Its job is confidence and socialisation, not teaching; completing it credits
-- nothing in the competency ledger.

-- Task-0 candidacy is a property a PM sets on an already-approved starter-work task. A task nobody
-- wanted is not a contribution, so it is a deliberate choice, never a default -- hence NOT NULL
-- DEFAULT FALSE, and existing rows stay ineligible.
ALTER TABLE starter_work_task_proposals
    ADD COLUMN IF NOT EXISTS task_zero_eligible BOOLEAN NOT NULL DEFAULT FALSE;

-- One Task 0 per hire per project, auto-assigned on environment readiness and undoable.
--
-- A Task 0 is a single real piece of wanted work (a typo, a doc fix), so a proposal is assigned to
-- at most one hire: the unique index on proposal_id stops the same work going to two people, whose
-- two pull requests would collide.
CREATE TABLE IF NOT EXISTS task_zero_assignments
(
    id          UUID PRIMARY KEY,
    hire_id     UUID        NOT NULL,
    project_id  UUID        NOT NULL,
    proposal_id UUID        NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_task_zero_assignments_hire_project
    ON task_zero_assignments (hire_id, project_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_task_zero_assignments_proposal
    ON task_zero_assignments (proposal_id);
