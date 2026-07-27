-- Starter tasks belong to a track.
--
-- P2 let a hire whose work nothing observes finish onboarding; they were still being offered
-- somebody else's job to start it. A Scrum Master's suggested first tasks were mined GitHub
-- issues -- correct work, wrong role.
--
-- NULL is the deliberate default and means "suits any track". That is how every task behaved
-- before tracks existed, and it is the only honest reading of a mined issue: mining cannot know
-- which role an issue suits, so it must not claim one. Existing rows are therefore left alone
-- rather than backfilled to 'engineering' -- guessing would quietly hide tasks from people who
-- can do them.
ALTER TABLE starter_work_task_proposals
    ADD COLUMN onboarding_track_key VARCHAR(255);

ALTER TABLE starter_work_task_proposals
    ADD CONSTRAINT fk_starter_task_onboarding_track
        FOREIGN KEY (onboarding_track_key) REFERENCES onboarding_tracks ("key");
