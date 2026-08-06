-- Whether somebody at the source is already assigned to an ingested issue.
--
-- What changed and why: starter work is work a hire can *take*, and an issue somebody else is
-- already on is not available however open it is. Jira boards assign in-progress tickets, so
-- without this the starter-work pool offered new hires work other people were doing.
--
-- Nullable, and null means "we do not know" rather than "nobody". GitHub issues have assignees this
-- system does not ingest; recording that absence as false would be the same defect as reading an
-- absent GitHub history as "beginner", and would have changed engineering behaviour, which P0's
-- whole claim was that it must not. Only a definite TRUE withholds an issue from mining.
ALTER TABLE artifacts
    ADD COLUMN has_assignee BOOLEAN;
