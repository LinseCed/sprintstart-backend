-- The hire writes on their own board.
--
-- Authored cards are the only ones that carry stored content. A live card's row holds nothing but
-- its own existence, because its content is re-read from services that already hold those facts
-- durably; a note has nowhere else for its text to live. JSON in one column rather than a table
-- per kind: these are small, they are read and written whole, and nothing ever queries inside them.
ALTER TABLE board_cards
    ADD COLUMN payload TEXT;

-- Several notes are several notes, so one-row-per-kind can no longer hold for everything. It still
-- has to hold for the rest: a live card is a single read, so a second copy would be the same card
-- twice, and uniqueness is also what makes "ensure this card exists" idempotent.
--
-- Enforced as a partial unique index over the non-authored kinds. Hibernate cannot express a
-- partial index, so it is absent from the entity mapping and BoardService enforces the same rule in
-- code -- which is what schema-from-entities contexts (the test suite) rely on.
ALTER TABLE board_cards
    DROP CONSTRAINT IF EXISTS uq_board_cards_board_kind;

CREATE UNIQUE INDEX uq_board_cards_board_singleton_kind
    ON board_cards (board_id, kind)
    WHERE kind NOT IN ('NOTE', 'LINK', 'CHECKLIST');
