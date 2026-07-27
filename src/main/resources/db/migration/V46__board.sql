-- The board: a hire's persistent working surface, one per (hire, project).
--
-- The buddy conversation opens fresh every visit -- the previous window is folded into the
-- mentor's private memory and never replayed. That was deliberate, and it left anything durable
-- the mentor shows you with nowhere to live. This is that home.
--
-- A live card's row holds no content: only that this hire wants this card, where it sits, and
-- whether it is still there. Content is re-read on every load from the same services the buddy's
-- tools read, so a card and the tool behind it cannot disagree -- and there is no second copy of
-- facts that already live somewhere durable to go stale. Authored cards (a note, a diagram) do
-- have content of their own and bring their payload column with them when they arrive.
CREATE TABLE boards (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL,
    project_id UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_boards_user_project UNIQUE (user_id, project_id)
);

CREATE TABLE board_cards (
    id         UUID PRIMARY KEY,
    board_id   UUID         NOT NULL REFERENCES boards (id) ON DELETE CASCADE,
    kind       VARCHAR(255) NOT NULL,
    -- AI or HIRE. Two owners is the whole model -- one hire and one asynchronous mentor -- so
    -- ownership is all that is needed to decide who may change a card, with no collaborative
    -- editing machinery behind it.
    owner      VARCHAR(255) NOT NULL,
    -- ACTIVE or DISMISSED. A dismissed card keeps its row rather than being deleted, and that is
    -- the entire mechanism behind sticky removal: the board goes on ensuring the relevant cards
    -- exist on every load without ever re-adding one the hire has already said no to.
    state      VARCHAR(255) NOT NULL,
    position   INTEGER      NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    -- Every kind so far is a single live read, so a second copy would be the same card twice --
    -- and this is also what makes "ensure this card exists" idempotent. Authored cards break it
    -- (several notes are several notes), and the slice that adds them relaxes this.
    CONSTRAINT uq_board_cards_board_kind UNIQUE (board_id, kind)
);

CREATE INDEX idx_board_cards_board ON board_cards (board_id);
