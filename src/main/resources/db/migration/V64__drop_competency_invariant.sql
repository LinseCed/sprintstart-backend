-- Drops competencies.invariant.
--
-- The column was added by V10 to feed the competency-graph change classifier: an edit touching an
-- "invariant" competency classified as ChangeClassification.INVARIANT and pushed to every hire
-- immediately instead of at their next session boundary.
--
-- That classifier, the change-history table it wrote to and the session-boundary propagation it
-- fed were all removed with the competency graph. Nothing has read this column since. It stayed
-- authorable, though -- the studio offered a PM a "Mandatory" checkbox whose stated effect
-- ("changes reach every hire straight away") no longer described anything, because competency
-- visibility is a plain findAll() and every edit is visible immediately either way.
--
-- Removing the column rather than leaving it unread: a stored value nobody consumes is an
-- invitation to write code that consumes it, and a PM was doing real work to set it.

ALTER TABLE competencies
    DROP COLUMN IF EXISTS invariant;
