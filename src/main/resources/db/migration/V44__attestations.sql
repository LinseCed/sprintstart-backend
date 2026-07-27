-- Attested evidence: a named person confirming a hire did real work.
--
-- Everything the ramp reads is composed on read from facts that already live somewhere durable.
-- An attestation is the first piece of evidence that exists nowhere else -- nobody records "the
-- retro was well run" until somebody is asked -- which is what makes a table the honest choice
-- here and unnecessary everywhere else.
--
-- This is what lets a role nothing observes finish onboarding at all. It is weaker evidence than a
-- merged pull request and is labelled so (Rigor.ATTESTED) everywhere it is read, rather than
-- blended into one number that would launder the difference.

CREATE TABLE attestations (
    id                UUID         PRIMARY KEY,
    hire_id           UUID         NOT NULL,
    project_id        UUID         NOT NULL,
    title             TEXT         NOT NULL,
    evidence_url      VARCHAR(2048),
    attester_id       UUID         NOT NULL,
    state             VARCHAR(32)  NOT NULL,
    requested_at      TIMESTAMPTZ  NOT NULL,
    first_response_at TIMESTAMPTZ,
    accepted_at       TIMESTAMPTZ,
    -- Rework, counted the same way a pull request's changes-requested is: work that took three
    -- passes must not read like work that took none, because autonomy asks exactly that.
    returned_count    INTEGER      NOT NULL DEFAULT 0,
    return_reason     TEXT,

    -- The rule that makes this evidence rather than a formality. Enforced at the service boundary
    -- too, with a message a person can act on; here so no future writer can bypass it.
    CONSTRAINT ck_attestation_not_self CHECK (attester_id <> hire_id)
);

-- The two reads this table has: a hire's own history on a project, and one attester's queue.
CREATE INDEX idx_attestations_hire_project ON attestations (hire_id, project_id);
CREATE INDEX idx_attestations_attester_state ON attestations (attester_id, state);

-- The delivery track can now measure its hires. It shipped in V43 admitting no evidence kind at
-- all, which was honest at the time -- nothing a delivery lead does is observable by the one
-- connector this system has -- and is exactly the gap attestation closes.
INSERT INTO onboarding_track_evidence_kinds (onboarding_track_id, evidence_kind)
SELECT id, 'ATTESTATION' FROM onboarding_tracks WHERE "key" = 'delivery';

-- Engineering gains it too. A developer's work is usually a merged pull request, but not all of it
-- is, and a track is a bundle of defaults rather than a cage: someone who ran the incident review
-- should be able to have that count.
INSERT INTO onboarding_track_evidence_kinds (onboarding_track_id, evidence_kind)
SELECT id, 'ATTESTATION' FROM onboarding_tracks WHERE "key" = 'engineering';
