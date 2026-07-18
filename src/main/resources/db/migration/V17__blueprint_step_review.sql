-- Backend #10 (Phase 5), slice 2: blueprint step-level proposal review. Extends the per-item
-- approve/reject pattern CompetencyEdgeProposal already has (Phase 2) to individual
-- BlueprintSteps -- a PM can now curate steps within a proposal independently of the whole
-- blueprint's own DRAFT/PROPOSED/ACTIVE decision. Rejected steps stay in the table (audit trail)
-- but are excluded from Blueprint.toSchema()'s output (what reaches personalization/regeneration)
-- from then on. NOTE: like V7, `blueprint_steps` is not created by any V* migration in this fork
-- (dev builds it via Hibernate ddl-auto) -- this documents the schema delta, kept in sync with
-- BlueprintStep / ProposalStatus.

ALTER TABLE blueprint_steps
    ADD COLUMN IF NOT EXISTS status VARCHAR(50) NOT NULL DEFAULT 'PROPOSED';

ALTER TABLE blueprint_steps
    ADD COLUMN IF NOT EXISTS decided_at TIMESTAMP;

ALTER TABLE blueprint_steps
    ADD COLUMN IF NOT EXISTS rejection_reason TEXT;

ALTER TABLE blueprint_steps
    DROP CONSTRAINT IF EXISTS chk_blueprint_steps_status;

ALTER TABLE blueprint_steps
    ADD CONSTRAINT chk_blueprint_steps_status
        CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED'));
