-- The human loop (Onboarding v2, slice 1).
--
-- Mentoring carries the largest measured effects in the onboarding literature, and until now the
-- product's answer to "who helps me" was a chatbot. These two tables are the human one.

-- One buddy per hire per project. A peer rather than a manager: what a hire is willing to admit
-- not knowing depends on who is listening.
--
-- Optional by design -- a project may have none, and everything else in the human loop still
-- works. An unassigned hire becomes an attention item of its own rather than a silent gap.
CREATE TABLE IF NOT EXISTS onboarding_buddies
(
    id                  UUID PRIMARY KEY,
    hire_id             UUID        NOT NULL,
    project_id          UUID        NOT NULL,
    buddy_id            UUID        NOT NULL,
    -- How often the pair is expected to speak. Contact *frequency*, not the existence of a buddy,
    -- is what tracked with outcomes, so this is a real setting rather than decoration.
    cadence_target_days INT         NOT NULL DEFAULT 7,
    assigned_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_buddy_not_self CHECK (hire_id <> buddy_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_onboarding_buddies_hire_project
    ON onboarding_buddies (hire_id, project_id);

CREATE INDEX IF NOT EXISTS idx_onboarding_buddies_buddy
    ON onboarding_buddies (buddy_id);

-- A conversation happened.
--
-- The first onboarding fact that cannot be derived from anything else: assignments, claimed tasks
-- and pull requests all leave durable traces the metrics read after the fact, but two people
-- talking leaves none. Recorded here or lost.
--
-- Rows survive the assignment being removed. The conversations happened, and deleting them would
-- rewrite the history the metrics are read from.
CREATE TABLE IF NOT EXISTS buddy_contacts
(
    id          UUID PRIMARY KEY,
    hire_id     UUID        NOT NULL,
    project_id  UUID        NOT NULL,
    -- Either side may log a contact; this keeps the record honest about which one did.
    recorded_by UUID        NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- For the pair, not for analysis. Nothing reads it.
    note        TEXT
);

CREATE INDEX IF NOT EXISTS idx_buddy_contacts_hire_project
    ON buddy_contacts (hire_id, project_id, occurred_at DESC);
