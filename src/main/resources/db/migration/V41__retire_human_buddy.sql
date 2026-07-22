-- Buddy-led onboarding, B2: retire the human buddy.
--
-- The assigned-peer-mentor loop (assignment, cadence, contact logging) is removed, not kept as a
-- control arm: the AI buddy is the onboarding, and the human in the loop is now only the PM,
-- asynchronously, through flag-to-PM (knowledge_requests, which stays). "Needed help" on the
-- autonomy exit is re-anchored on that surviving channel, so this history is no longer read from.
DROP TABLE IF EXISTS buddy_contacts;
DROP TABLE IF EXISTS onboarding_buddies;
