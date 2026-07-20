-- The competency a generated step teaches, carried over from the blueprint step it came from.
--
-- This is what turns a step into a *module*: personalization creates a default Verification for
-- every step that has one, and the competency path uses that verification to point the matching
-- graph node at the step. Without it the key that blueprint generation attaches was dropped the
-- moment a path was generated, so every node rendered as non-openable.
--
-- Existing rows stay null: they predate the bridge and have no blueprint step to recover a key
-- from. Regenerating a path fills them in.
ALTER TABLE onboarding_steps
    ADD COLUMN IF NOT EXISTS competency_key VARCHAR(255);
