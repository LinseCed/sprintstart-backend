-- Backend #10 (Phase 5): buddy session persistence. Makes the stateless AI buddy endpoint
-- (ai#6, sprintstart-ai PR #13) durable across visits -- one continuous session per user, unlike
-- the general-purpose chats table's multiple user-created chats. No persisted citations for v1
-- (would need the chat module's internal ArtifactLookupService, not exposed cross-module); live
-- citation SSE events still reach the client during the stream. Canonical hand-maintained schema
-- delta; same ddl-auto caveat as prior migrations.

CREATE TABLE IF NOT EXISTS buddy_sessions (
    id         UUID PRIMARY KEY,
    user_id    UUID      NOT NULL,
    created_at TIMESTAMP NOT NULL,

    CONSTRAINT uq_buddy_sessions_user UNIQUE (user_id)
);

CREATE TABLE IF NOT EXISTS buddy_messages (
    id         UUID PRIMARY KEY,
    session_id UUID        NOT NULL,
    role       VARCHAR(50) NOT NULL,
    content    TEXT        NOT NULL,
    created_at TIMESTAMP   NOT NULL,

    CONSTRAINT fk_buddy_messages_session
        FOREIGN KEY (session_id) REFERENCES buddy_sessions (id),

    CONSTRAINT chk_buddy_messages_role
        CHECK (role IN ('USER', 'ASSISTANT'))
);

CREATE INDEX IF NOT EXISTS idx_buddy_messages_session
    ON buddy_messages(session_id);
