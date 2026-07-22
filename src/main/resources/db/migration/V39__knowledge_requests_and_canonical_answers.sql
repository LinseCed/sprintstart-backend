-- Buddy Enhancement (F4): the growth loop.
--
-- When the buddy cannot answer a question, the hire may escalate it to a PM. The PM's answer is
-- kept as durable, editable knowledge and served back to the next hire who asks something like it,
-- so the AI mentor gets smarter every time a human fills a gap.

-- The event: a question a hire chose to send to a person. The queue a PM works through.
CREATE TABLE IF NOT EXISTS knowledge_requests
(
    id                  UUID PRIMARY KEY,
    project_id          UUID        NOT NULL,
    hire_id             UUID        NOT NULL,
    question            TEXT        NOT NULL,
    status              VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    answered_by         UUID,
    answered_at         TIMESTAMPTZ,
    canonical_answer_id UUID
);

-- The PM inbox reads the open queue per project, worst/oldest first.
CREATE INDEX IF NOT EXISTS idx_knowledge_requests_project_status
    ON knowledge_requests (project_id, status);

-- A hire reads their own escalations and the answers they got.
CREATE INDEX IF NOT EXISTS idx_knowledge_requests_hire
    ON knowledge_requests (hire_id);

-- The knowledge: a human's durable answer, the source of truth, editable when reality changes.
-- One answer can resolve many requests (the same gap gets hit repeatedly).
CREATE TABLE IF NOT EXISTS canonical_answers
(
    id         UUID PRIMARY KEY,
    project_id UUID        NOT NULL,
    question   TEXT        NOT NULL,
    answer     TEXT        NOT NULL,
    author_id  UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- The buddy's canonical-answer tool searches per project.
CREATE INDEX IF NOT EXISTS idx_canonical_answers_project
    ON canonical_answers (project_id);
