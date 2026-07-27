-- The mentor draws on the board.
--
-- A DIAGRAM card is live like the rest -- the row stores the *question*, never the picture, so a
-- diagram cannot describe code that has since moved. That question is the one thing a model chooses
-- about any card, and it is worth naming the rule it extends rather than leaving it implied:
--
--     The model may choose the question. It never writes the answer.
--
-- The subject aims retrieval and is asserted nowhere. Every node comes back derived from the corpus
-- with the citation proving it, and an ungrounded one is dropped before it is ever stored.
ALTER TABLE board_cards
    ADD COLUMN subject TEXT;

-- Two subjects are two diagrams, so one-row-per-kind stops being the whole identity for this kind.
-- It still holds for every other live card: one is a single read, so a second copy would be the same
-- card twice, and uniqueness is what makes "ensure this card exists" idempotent.
--
-- Split into two partial indexes rather than one over (board_id, kind, subject), because a NULL
-- subject does not conflict with a NULL subject in Postgres -- so a single index would silently stop
-- constraining every kind that has no subject, which is all of them but this one.
DROP INDEX IF EXISTS uq_board_cards_board_singleton_kind;

CREATE UNIQUE INDEX uq_board_cards_board_singleton_kind
    ON board_cards (board_id, kind)
    WHERE kind NOT IN ('NOTE', 'LINK', 'CHECKLIST', 'DIAGRAM');

-- Lower-cased, because "How auth works" asked twice with different capitals is one question -- and
-- because a dismissal has to stick against the way somebody phrases it the second time.
CREATE UNIQUE INDEX uq_board_cards_board_diagram_subject
    ON board_cards (board_id, lower(subject))
    WHERE kind = 'DIAGRAM';

-- The last picture drawn for a diagram card: a cache, and nothing more.
--
-- Every other live card hydrates from a database read costing nothing; a diagram costs a generation,
-- and a board card hydrates on every page load. So the picture is kept, and kept the way an
-- orientation packet is: validated, never trusted. Every revalidation sends corpus_fingerprint, an
-- unchanged corpus is answered without any retrieval or generation, and a corpus that has moved is
-- redrawn. Age is not staleness -- a diagram of code nobody has touched is current.
--
-- JSON in one column rather than tables for nodes, edges, citations and sources: it is read and
-- written whole and nothing ever queries inside it. Unlike an authored note, a payload that will not
-- decode is a cache miss rather than a failure -- everything in it is derivable and none of it was
-- anybody's work.
CREATE TABLE board_diagrams
(
    card_id           UUID PRIMARY KEY REFERENCES board_cards (id) ON DELETE CASCADE,
    corpus_fingerprint TEXT,
    model             TEXT,
    payload           TEXT        NOT NULL,
    assembled_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
