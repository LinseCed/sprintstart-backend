-- Buddy-led onboarding, B0: the buddy's memory, and module pages keep their citations.
--
-- The buddy is now the hire's front door, so two long-standing tolerations become load-bearing:
-- the whole transcript was re-sent to the AI service on every turn (unbounded), and a module
-- page's citations were thrown away on persist (the AI grounds every page, the backend kept the
-- prose and dropped the provenance -- the exact failure task_orientation_citations was created
-- to avoid). Both are fixed here.

-- A running summary of the oldest messages in the session, plus how many of them it covers.
-- Only the window *after* summarized_count is sent to the AI service, so a long conversation no
-- longer means an unbounded prompt. The full transcript stays in buddy_messages -- the summary
-- is a prompt-shaping device, never the record.
ALTER TABLE buddy_sessions
    ADD COLUMN IF NOT EXISTS summary           TEXT,
    ADD COLUMN IF NOT EXISTS summarized_count  INTEGER NOT NULL DEFAULT 0;

-- Where one claim on a module page came from. Mirrors task_orientation_citations: stored, not
-- derived, so a reader (or the buddy teaching from the page) can follow a claim back to the
-- material it restates. Pages authored by a PM may carry none -- a human naming no source is a
-- choice, not dropped provenance.
CREATE TABLE IF NOT EXISTS module_page_citations
(
    id         UUID PRIMARY KEY,
    page_id    UUID    NOT NULL REFERENCES module_pages (id) ON DELETE CASCADE,
    filename   TEXT    NOT NULL,
    chunk_id   TEXT    NOT NULL,
    source_url TEXT,
    position   INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_module_page_citations_page ON module_page_citations (page_id);
