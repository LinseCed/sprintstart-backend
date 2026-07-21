-- Human-authorable task orientation (Onboarding v2 follow-up).
--
-- Orientation packets could until now only be AI-assembled; this makes them human-authorable by a PM
-- (from the Starter Work page) or by the hire fixing their own task's orientation in place. A packet's
-- origin decides how it is served: an AI packet is still cached and revalidated against the corpus
-- fingerprint, while a HUMAN packet is a person's own words and is served exactly as written -- never
-- re-assembled, never auto-deleted as stale. The staleness machinery is an AI guardrail a human is not
-- bound by.
--
-- Existing rows are AI-assembled, so the column defaults to 'AI'; that keeps every cached packet
-- behaving exactly as before this change.
ALTER TABLE task_orientation_packets
    ADD COLUMN IF NOT EXISTS origin TEXT NOT NULL DEFAULT 'AI';
