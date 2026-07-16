-- Phase 1 (backend #6): skill-chat assessment session.
-- Canonical hand-maintained schema kept in sync with the JPA entities
-- SkillAssessmentSession and SkillAssessmentTurn. One session tracks a hire's
-- in-progress adaptive interview; turns are ordered by turn_index and mirror
-- the AI service's stateless history (question = interviewer turn, answer =
-- candidate turn, answer null until the candidate has replied).

CREATE TABLE IF NOT EXISTS skill_assessment_sessions (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL,
    status     VARCHAR(50) NOT NULL,
    created_at TIMESTAMP   NOT NULL,
    updated_at TIMESTAMP   NOT NULL,

    CONSTRAINT chk_skill_assessment_sessions_status
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);

CREATE TABLE IF NOT EXISTS skill_assessment_turns (
    id         UUID PRIMARY KEY,
    session_id UUID    NOT NULL,
    turn_index INTEGER NOT NULL,
    question   TEXT    NOT NULL,
    answer     TEXT,
    created_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_skill_assessment_turns_session
        FOREIGN KEY (session_id) REFERENCES skill_assessment_sessions (id),

    CONSTRAINT uq_skill_assessment_turns_session_turn_index
        UNIQUE (session_id, turn_index)
);

CREATE INDEX IF NOT EXISTS idx_skill_assessment_sessions_user_id
    ON skill_assessment_sessions(user_id);

CREATE INDEX IF NOT EXISTS idx_skill_assessment_turns_session_id
    ON skill_assessment_turns(session_id);
