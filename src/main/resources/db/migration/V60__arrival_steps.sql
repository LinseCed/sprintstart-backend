-- Arrival: the things that have to be true before a hire can work -- an account, an access grant,
-- a machine that builds.
--
-- Onboarding v2 optimised for reaching a first merged pull request and assumed the hire already had
-- everything needed to start: a GitHub account (typed in as free text, with no step that gets you
-- one), repository access, a working environment. So somebody who could not clone the repository
-- still got a buddy conversation about a task they could not begin -- and because the stall
-- detector reads contributions, they read as *calm* rather than *blocked*.
--
-- This is NOT the per-user step tree C4 deleted, and it is NOT a gate.
--
--   * Not the step tree: that hung content off a per-user OnboardingPath, so there was no "the step
--     for X", only N private copies nobody could maintain. Here the definition is shared
--     (arrival_steps) and only the state is per hire (arrival_step_states).
--   * Not a gate: nothing consults these rows before serving a hire. An outstanding step changes
--     what somebody is shown, never what they may do. `NodeState.LOCKED` was removed from its enum
--     so a gate could not come back by accident, and this does not bring one back.
--
-- Scope: project_id NULL means company-wide. Account creation and paperwork are identical on every
-- project, and making each PM re-author them is the effort this model exists to avoid. That follows
-- the rule already load-bearing elsewhere here -- absent scope is not excluded scope -- which a null
-- track (suits any role), unscoped corpus material (visible to every project) and a null
-- connector_id already follow.
--
-- Rigor is recorded, never blended. A step the system observed and a step somebody ticked are
-- different facts; the previous model's `progressPercentage` counted them the same and that is what
-- made its progress reporting meaningless. Nothing may reduce these to one number.
--
-- No backfill: this workspace is dev-only and the database is wiped on each schema change, so this
-- file is the record of what changed and why, not a step to run.

CREATE TABLE arrival_steps (
    id          UUID PRIMARY KEY,
    "key"       VARCHAR(255) NOT NULL,
    project_id  UUID         NULL,
    title       VARCHAR(255) NOT NULL,
    description TEXT         NULL,
    href        VARCHAR(255) NULL,
    position    INTEGER      NOT NULL DEFAULT 0,
    settled_by  VARCHAR(32)  NOT NULL DEFAULT 'DECLARED',
    provenance  VARCHAR(32)  NOT NULL DEFAULT 'PM',
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- TWO partial unique indexes, not one composite index, and this is the whole reason:
-- **NULL does not conflict with NULL in Postgres.** A single UNIQUE (key, project_id) would
-- constrain project-scoped rows and silently permit unlimited duplicate company-wide ones -- which
-- is every row this slice creates. The same trap was hit once already by board diagrams, whose
-- subject column is null for every kind but one.
--
-- Hibernate cannot express a partial unique index at all, and the test suite builds its schema from
-- the entities rather than from these files, so ArrivalStepService enforces the same rule in code.
-- Neither guard is redundant: the index protects the database, the service protects the tests.
CREATE UNIQUE INDEX ux_arrival_steps_company_key
    ON arrival_steps ("key")
    WHERE project_id IS NULL;

CREATE UNIQUE INDEX ux_arrival_steps_project_key
    ON arrival_steps ("key", project_id)
    WHERE project_id IS NOT NULL;

CREATE INDEX ix_arrival_steps_project ON arrival_steps (project_id);

-- One row per hire per step they have settled. The row's existence IS the state: there is no status
-- column, because the only value it could hold today is "settled", and a single-valued enum is the
-- dead wiring this codebase has shipped often enough to have a rule against. Absence means "not
-- settled yet", which is the normal day-one state rather than missing data.
CREATE TABLE arrival_step_states (
    id         UUID PRIMARY KEY,
    user_id    UUID         NOT NULL,
    -- Deliberately NOT a foreign key to arrival_steps. State points at the step's stable key, which
    -- is the ledger pattern user_competency_states and verification_attempts already follow, and it
    -- is why five separate deletions in this codebase left hires' history intact. A cascade would
    -- make deleting a step from an authoring screen quietly destroy other people's records; keyed
    -- this way, re-adding the same key restores them.
    step_key   VARCHAR(255) NOT NULL,
    project_id UUID         NULL,
    rigor      VARCHAR(32)  NOT NULL,
    settled_at TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_arrival_step_states_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- Same NULL-versus-NULL reasoning as above: a hire must not be able to settle the same step twice.
CREATE UNIQUE INDEX ux_arrival_step_states_company
    ON arrival_step_states (user_id, step_key)
    WHERE project_id IS NULL;

CREATE UNIQUE INDEX ux_arrival_step_states_project
    ON arrival_step_states (user_id, step_key, project_id)
    WHERE project_id IS NOT NULL;

CREATE INDEX ix_arrival_step_states_user ON arrival_step_states (user_id);
