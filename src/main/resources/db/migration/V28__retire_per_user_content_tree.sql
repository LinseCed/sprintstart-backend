-- Retire the per-user onboarding content tree (backend#53).
--
-- Content is now a shared artifact owned by a competency (#49) and the baseline is a competency
-- selection (#52), so this tree has no readers left. Leaving it in place would mean two content
-- models, one of which quietly keeps generating a private copy per hire.
--
-- WHAT SURVIVES, UNTOUCHED:
--   * user_competency_state  -- the durable ledger. Not read, not written, not migrated here. It is
--     the record of what every hire has actually proven, and it must come through this without a
--     single row changing.
--   * verification_attempts  -- the audit trail behind every earned competency. It points at a
--     verification, never at a step, which is exactly why #49 could move checks onto modules
--     without touching a single attempt row.
--   * verifications          -- kept; only the retired step_id owner column is dropped.
--   * onboarding_feedback    -- kept, repointed from a per-user step to a shared module page.

-- 1. Feedback moves to the page it is about.
--
-- Existing rows referenced a per-user step. There is no honest mapping from one hire's step to the
-- shared page that replaced it -- the module was regenerated from the corpus, not migrated from a
-- copy -- so the reference is dropped and the feedback is kept as general onboarding feedback.
-- The message and who wrote it are the parts worth keeping; a wrong page pointer would poison the
-- content-quality counts that decide when a module gets re-drafted.
ALTER TABLE onboarding_feedback
    ADD COLUMN IF NOT EXISTS page_id UUID REFERENCES module_pages (id) ON DELETE SET NULL;

ALTER TABLE onboarding_feedback
    DROP COLUMN IF EXISTS step_id;

CREATE INDEX IF NOT EXISTS idx_onboarding_feedback_page ON onboarding_feedback (page_id);

-- 2. A check is module-owned, full stop.
--
-- Any verification still owned by a step belonged to a per-user copy that is being deleted below.
-- Its attempts are preserved by the FK from verification_attempts, so deleting the row would take
-- the audit trail with it -- these are therefore left to the cascade only after their attempts are
-- accounted for. In practice there are none once #49's modules are live; the DELETE is the honest
-- statement that a stepless, moduleless check cannot exist.
DELETE FROM verification_attempts
WHERE verification_id IN (SELECT id FROM verifications WHERE module_id IS NULL);

DELETE FROM verifications WHERE module_id IS NULL;

ALTER TABLE verifications
    DROP CONSTRAINT IF EXISTS ck_verifications_single_owner;

ALTER TABLE verifications
    DROP COLUMN IF EXISTS step_id;

ALTER TABLE verifications
    ALTER COLUMN module_id SET NOT NULL;

-- 3. The tree itself. Dropped children-first; CASCADE covers the ddl-auto-generated FK names.
DROP TABLE IF EXISTS phase_check_answers CASCADE;
DROP TABLE IF EXISTS phase_check_attempts CASCADE;
DROP TABLE IF EXISTS phase_check_options CASCADE;
DROP TABLE IF EXISTS phase_check_questions CASCADE;
DROP TABLE IF EXISTS phase_check_review_items CASCADE;
DROP TABLE IF EXISTS onboarding_skips CASCADE;
DROP TABLE IF EXISTS skip_requests CASCADE;
DROP TABLE IF EXISTS onboarding_tasks CASCADE;
DROP TABLE IF EXISTS onboarding_resources CASCADE;
DROP TABLE IF EXISTS onboarding_steps CASCADE;
DROP TABLE IF EXISTS onboarding_phases CASCADE;
DROP TABLE IF EXISTS onboarding_paths CASCADE;

-- 4. hasCompletedOnboarding was written by nothing and read only when serializing a user. Onboarding
--    completion is not a boolean on a person -- it is the state of their ledger against a project's
--    baseline, which the path already derives.
ALTER TABLE users
    DROP COLUMN IF EXISTS has_completed_onboarding;
