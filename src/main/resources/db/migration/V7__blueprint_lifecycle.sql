-- Phase 0b (backend #14): blueprint proposal-only lifecycle + PM approval.
-- Adds owner_id/origin to blueprints and widens the status domain to the full
-- DRAFT -> PROPOSED -> ACTIVE -> ARCHIVED lifecycle. Kept in sync with the JPA
-- entities Blueprint / BlueprintStatus / BlueprintOrigin.
--
-- NOTE: the `blueprints` and `blueprint_steps` tables are not created by any V*
-- migration in this fork (dev builds them via Hibernate ddl-auto). These ALTERs
-- therefore document the lifecycle delta rather than run against a migration-built
-- schema; how prod applies this is tracker #12 open question 4. (Issue text said V4;
-- V4-V6 already exist here, so this is V7.)

ALTER TABLE blueprints
    ADD COLUMN IF NOT EXISTS origin VARCHAR(50) NOT NULL DEFAULT 'AI_PROPOSED';

ALTER TABLE blueprints
    ADD COLUMN IF NOT EXISTS owner_id UUID;

-- Backfill any pre-existing rows to a sensible origin (they predate manual authoring).
UPDATE blueprints SET origin = 'AI_PROPOSED' WHERE origin IS NULL;

-- Widen the status domain to the full proposal-only lifecycle.
ALTER TABLE blueprints
    DROP CONSTRAINT IF EXISTS chk_blueprints_status;

ALTER TABLE blueprints
    ADD CONSTRAINT chk_blueprints_status
        CHECK (status IN ('DRAFT', 'PROPOSED', 'ACTIVE', 'ARCHIVED'));

ALTER TABLE blueprints
    DROP CONSTRAINT IF EXISTS chk_blueprints_origin;

ALTER TABLE blueprints
    ADD CONSTRAINT chk_blueprints_origin
        CHECK (origin IN ('PM', 'AI_PROPOSED'));
