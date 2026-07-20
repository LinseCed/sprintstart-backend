-- Task-scoped orientation (Onboarding v2, slice 3).
--
-- What the project already says about doing one task, assembled by the AI service from existing
-- material and cached here. Deliberately unlike competency_modules: no version, no status, no
-- approval. A packet is disposable, so these tables are a cache -- losing a row costs one AI call,
-- not a PM's work, and nobody stands between a hire and their orientation.

-- One packet per (task, project), never per hire: orientation is a property of the task, so two
-- people who claim it read the same thing and can talk about it.
--
-- corpus_fingerprint is what makes the cache honest. It is sent back to the AI service on every
-- read: an unchanged corpus is answered without retrieval, and a corpus that has moved is
-- re-assembled rather than served from here. A packet describing code that has since changed is
-- worse than no packet, because a hire cannot tell.
CREATE TABLE IF NOT EXISTS task_orientation_packets
(
    id                 UUID PRIMARY KEY,
    task_proposal_id   UUID        NOT NULL,
    project_id         UUID        NOT NULL,
    task_title         TEXT        NOT NULL,
    summary            TEXT,
    corpus_fingerprint TEXT,
    model              TEXT,
    assembled_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_task_orientation_packets_task_project
    ON task_orientation_packets (task_proposal_id, project_id);

-- Sections are segmented by the step somebody is on (set up, find the code, make the change, check
-- locally, open the PR) rather than by topic, so a hire returning on day three does not re-read
-- setup. position is the render order the AI service already sorted into.
CREATE TABLE IF NOT EXISTS task_orientation_sections
(
    id        UUID PRIMARY KEY,
    packet_id UUID    NOT NULL REFERENCES task_orientation_packets (id) ON DELETE CASCADE,
    step      TEXT    NOT NULL,
    title     TEXT    NOT NULL,
    body      TEXT    NOT NULL,
    position  INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_task_orientation_sections_packet
    ON task_orientation_sections (packet_id);

-- Provenance is the trust mechanism, so it is stored, not derived: a hire has to be able to open
-- the source and check the claim. A section with no rows here should not exist -- the AI service
-- drops ungrounded sections before returning them.
CREATE TABLE IF NOT EXISTS task_orientation_citations
(
    id         UUID PRIMARY KEY,
    section_id UUID    NOT NULL REFERENCES task_orientation_sections (id) ON DELETE CASCADE,
    filename   TEXT    NOT NULL,
    chunk_id   TEXT    NOT NULL,
    source_url TEXT,
    position   INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_task_orientation_citations_section
    ON task_orientation_citations (section_id);

-- The packet's own statement of the ground it stands on. Distinct from a citation, which says
-- "this claim came from here": this stays useful when a section a hire wanted was dropped for want
-- of grounding, and gives "this is out of date" somewhere to point.
CREATE TABLE IF NOT EXISTS task_orientation_sources
(
    id            UUID PRIMARY KEY,
    packet_id     UUID    NOT NULL REFERENCES task_orientation_packets (id) ON DELETE CASCADE,
    filename      TEXT    NOT NULL,
    source_url    TEXT,
    artifact_type TEXT,
    position      INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_task_orientation_sources_packet
    ON task_orientation_sources (packet_id);
