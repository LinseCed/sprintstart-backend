-- Backend #7 follow-up: AI-proposed competency graph elements awaiting PM review.
-- Per-item review (unlike blueprints' whole-scope-version proposals): each node and
-- each edge is independently PROPOSED/APPROVED/REJECTED. Canonical hand-maintained
-- schema kept in sync with CompetencyProposal / CompetencyEdgeProposal / ProposalStatus.

CREATE TABLE IF NOT EXISTS competency_proposals (
    id                 UUID PRIMARY KEY,
    "key"              VARCHAR(255) NOT NULL,
    label              VARCHAR(255) NOT NULL,
    description        TEXT,
    kind               VARCHAR(50)  NOT NULL,
    repo_ref           VARCHAR(2048),
    status             VARCHAR(50)  NOT NULL,
    corpus_fingerprint VARCHAR(255),
    created_at         TIMESTAMP    NOT NULL,
    decided_at         TIMESTAMP,
    rejection_reason   TEXT,

    CONSTRAINT chk_competency_proposals_kind
        CHECK (kind IN ('SKILL', 'CONCEPT', 'CONTRIBUTION', 'POLICY', 'CONNECTION', 'CULTURE', 'CHECKPOINT')),

    CONSTRAINT chk_competency_proposals_status
        CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED'))
);

CREATE TABLE IF NOT EXISTS competency_edge_proposals (
    id                 UUID PRIMARY KEY,
    from_key           VARCHAR(255) NOT NULL,
    to_key             VARCHAR(255) NOT NULL,
    kind               VARCHAR(50)  NOT NULL,
    rationale          TEXT,
    status             VARCHAR(50)  NOT NULL,
    corpus_fingerprint VARCHAR(255),
    created_at         TIMESTAMP    NOT NULL,
    decided_at         TIMESTAMP,
    rejection_reason   TEXT,

    CONSTRAINT chk_competency_edge_proposals_kind
        CHECK (kind IN ('PREREQUISITE', 'RELATED')),

    CONSTRAINT chk_competency_edge_proposals_status
        CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED'))
);

CREATE INDEX IF NOT EXISTS idx_competency_proposals_status
    ON competency_proposals(status);

CREATE INDEX IF NOT EXISTS idx_competency_edge_proposals_status
    ON competency_edge_proposals(status);
