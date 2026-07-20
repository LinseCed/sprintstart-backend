-- The GitHub account a user contributes as.
--
-- Artifact verification attributes a submitted pull request to a hire by comparing its author
-- against this login; without it, a submitted PR cannot be tied to the person submitting it.
-- Nullable and unset for existing users: nobody is retro-assigned an identity, they declare it
-- (or a PM sets it), and until then artifact checks tell them to.
ALTER TABLE sprintstart_users
    ADD COLUMN IF NOT EXISTS github_login VARCHAR(39);

-- SELF_DECLARED (typed by the user) or PM_CONFIRMED (set/corrected by a PM/HR). Neither proves
-- account ownership -- that would need a federated GitHub login in Keycloak -- so this records
-- only what is actually known.
ALTER TABLE sprintstart_users
    ADD COLUMN IF NOT EXISTS github_login_source VARCHAR(20);

-- Logins are stored lower-cased, so a plain unique index is enough to keep two users from
-- claiming the same GitHub account (which would make PR attribution ambiguous).
CREATE UNIQUE INDEX IF NOT EXISTS idx_sprintstart_users_github_login
    ON sprintstart_users (github_login)
    WHERE github_login IS NOT NULL;
