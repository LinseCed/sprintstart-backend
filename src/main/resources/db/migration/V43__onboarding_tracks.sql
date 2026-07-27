-- Onboarding tracks: what onboarding means for one kind of role.
--
-- Onboarding terminated in a merged pull request, which made "this hire writes code" part of the
-- definition of progress. A track states the two things that actually differ between roles -- what
-- counts as their work, and what to call it -- while the ramp, the metrics and the ledger stay
-- shared.

CREATE TABLE onboarding_tracks (
    id                        UUID         PRIMARY KEY,
    -- Unique rather than the primary key: rows are joined by id, but everything *outside* this
    -- table points at a track by its stable key, the same convention competencies use.
    "key"                     VARCHAR(255) NOT NULL UNIQUE,
    label                     VARCHAR(255) NOT NULL,
    contribution_noun         VARCHAR(255) NOT NULL,
    contribution_noun_plural  VARCHAR(255) NOT NULL,
    contribution_verb_past    VARCHAR(255) NOT NULL,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE onboarding_track_evidence_kinds (
    onboarding_track_id UUID         NOT NULL REFERENCES onboarding_tracks (id) ON DELETE CASCADE,
    evidence_kind       VARCHAR(64)  NOT NULL,
    PRIMARY KEY (onboarding_track_id, evidence_kind)
);

-- The default track. Every existing role is migrated onto it below, so nothing about how anybody
-- is onboarding today changes.
INSERT INTO onboarding_tracks (id, "key", label, contribution_noun, contribution_noun_plural, contribution_verb_past)
VALUES ('11111111-1111-4111-8111-111111111111', 'engineering', 'Engineering',
        'change', 'changes', 'merged');

INSERT INTO onboarding_track_evidence_kinds (onboarding_track_id, evidence_kind)
VALUES ('11111111-1111-4111-8111-111111111111', 'PULL_REQUEST');

-- A second track so the mechanism is real rather than theoretical: a PM can point a role at it
-- today and the vocabulary follows. It deliberately declares NO evidence kind -- nothing a delivery
-- lead does is observable by the one connector this system has. That is the honest state, and it is
-- what the attestation slice exists to fix; inventing an evidence kind it cannot produce would put
-- a fake number under the north-star metric.
INSERT INTO onboarding_tracks (id, "key", label, contribution_noun, contribution_noun_plural, contribution_verb_past)
VALUES ('22222222-2222-4222-8222-222222222222', 'delivery', 'Agile delivery',
        'ceremony', 'ceremonies', 'facilitated');

ALTER TABLE sprintstart_project_roles
    ADD COLUMN onboarding_track_key VARCHAR(255);

-- Backfill, not a default: every role that exists today was authored when engineering was the only
-- thing onboarding could mean, so saying so explicitly is truthful. New roles start NULL, which
-- resolves to the default track anyway -- a PM who has not thought about tracks is not blocked.
UPDATE sprintstart_project_roles
SET onboarding_track_key = 'engineering'
WHERE onboarding_track_key IS NULL;

ALTER TABLE sprintstart_project_roles
    ADD CONSTRAINT fk_project_role_onboarding_track
        FOREIGN KEY (onboarding_track_key) REFERENCES onboarding_tracks ("key");
