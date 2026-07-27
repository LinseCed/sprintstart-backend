-- The mentor can put cards on the board, and the board says which ones it did.
--
-- NULL means the board keeps this card as part of the baseline -- the set every hire gets without
-- anybody deciding. A timestamp means the mentor placed it, and the board can say so. That is not
-- a twin of `owner`, which answers who may *change* a card: this answers where it came from, and
-- it is user-visible. Claiming "your buddy added this" about a card nobody chose would be the
-- board's first lie, so the existing rows stay NULL rather than being backfilled to now().
ALTER TABLE board_cards
    ADD COLUMN placed_at TIMESTAMPTZ;
