-- Backend #9: AI-mined starter-work task proposals (contribution-node sourcing) awaiting PM
-- review. Mirrors the competency_proposals pattern from #7 (V11), adapted to a single AI-mined
-- GitHub issue per row instead of node+edge pairs. Approving a proposal creates a real
-- CONTRIBUTION Competency plus PREREQUISITE edges from each tagged competency key. Canonical
-- hand-maintained schema kept in sync with StarterWorkTaskProposal / ProposalStatus.

CREATE TABLE IF NOT EXISTS starter_work_task_proposals (
    id               UUID PRIMARY KEY,
    source_id        VARCHAR(512)  NOT NULL,
    title            VARCHAR(1024) NOT NULL,
    summary          TEXT,
    rationale        TEXT,
    source_url       VARCHAR(2048),
    status           VARCHAR(50)   NOT NULL,
    created_at       TIMESTAMP     NOT NULL,
    decided_at       TIMESTAMP,
    rejection_reason TEXT,

    CONSTRAINT uq_starter_work_task_proposals_source_id UNIQUE (source_id),

    CONSTRAINT chk_starter_work_task_proposals_status
        CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED'))
);

CREATE TABLE IF NOT EXISTS starter_work_task_proposal_competency_keys (
    starter_work_task_proposal_id UUID         NOT NULL,
    competency_key                VARCHAR(255) NOT NULL,

    CONSTRAINT fk_starter_work_task_proposal_competency_keys_proposal
        FOREIGN KEY (starter_work_task_proposal_id) REFERENCES starter_work_task_proposals (id)
);

CREATE INDEX IF NOT EXISTS idx_starter_work_task_proposals_status
    ON starter_work_task_proposals(status);

CREATE INDEX IF NOT EXISTS idx_starter_work_task_proposal_competency_keys_proposal_id
    ON starter_work_task_proposal_competency_keys(starter_work_task_proposal_id);
