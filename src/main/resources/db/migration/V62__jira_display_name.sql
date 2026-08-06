-- The name a user appears under in a connected issue tracker.
--
-- What changed and why: role tracks P4's evidence half needs to attribute an ingested Jira issue to
-- a hire, and the ingested data carries only the assignee's *display name* -- the connector parses
-- displayName/active/created/updated and drops Jira's accountId. So this is the attribution key,
-- the tracker counterpart of users.github_login.
--
-- Unique for the reason github_login is, and it matters more here. A wrong GitHub login silently
-- credits a hire with nothing; two users claiming one Jira display name would credit one person's
-- issues to the other. Uniqueness is enforced in JiraDisplayNameService as well, because the test
-- suite builds schema from entities and never runs migrations.
--
-- Not defended by either: a namesake inside Jira itself. Two real Jira accounts sharing a display
-- name are indistinguishable in what is ingested. Parsing accountId in the connector is the fix if
-- anybody hits it.
ALTER TABLE users
    ADD COLUMN jira_display_name VARCHAR(255);

ALTER TABLE users
    ADD CONSTRAINT uk_users_jira_display_name UNIQUE (jira_display_name);
