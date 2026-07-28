-- Roles become a fact about a person *on a project*, not about a person.
--
-- `user_project_roles` had no project dimension at all, so "developer" was something you were
-- everywhere or nowhere: somebody who ships code on one project and runs delivery on another could
-- not be described, and the onboarding track derived from their role had to pick one or give up.
-- `user_project_assignment_roles` has existed since V4 for exactly this, and had **no writer** --
-- it has been empty in every deployment since it was created, which is why the admin project user
-- list reported every member as holding no role at all.
--
-- This backfills it from the flat set and drops the flat one.
--
-- !! THIS MIGRATION MOVES DATA AND MUST BE RUN. !!
-- There is no Flyway in this service (`ddl-auto: update`, and these files are applied by hand -- see
-- the workspace notes' open question 4). `update` never drops, so deploying the code without running
-- this leaves `user_project_roles` populated and `user_project_assignment_roles` empty, and every
-- role in the application silently disappears: role badges vanish, the user search's role filter
-- matches nobody, and every hire's onboarding track falls back to the default. Run it with the
-- deploy, not after it.
--
-- The backfill gives every assignment every role its user held. That is the only honest expansion of
-- a set that never recorded which project it applied to: assuming somebody's "developer" applied to
-- all their projects matches how the value was actually being read before this (globally), so nobody
-- gains or loses a role today. Where that is wrong for a person on several projects, it is now
-- *correctable* -- which it was not before.
INSERT INTO user_project_assignment_roles (user_id, project_id, role_id)
SELECT up.user_id, up.project_id, upr.role_id
FROM user_projects up
JOIN user_project_roles upr ON upr.user_id = up.user_id
ON CONFLICT DO NOTHING;

-- Roles held by somebody on no project have nowhere to go, and nothing could ever have read them:
-- every reader resolved a role through a project. They are dropped with the table.
DROP TABLE IF EXISTS user_project_roles;
