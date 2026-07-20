-- Who authored an artifact at the source, as a GitHub login (lower-cased).
--
-- Populated for issues and pull requests only: those come from the GraphQL API, which returns a
-- real `author.login`. Commits are parsed from `git log --pretty=%an` -- a git author *name*, not
-- a GitHub account -- and files have no single author, so both stay null rather than storing
-- something that merely looks like a login and would attribute work to the wrong person.
--
-- Existing rows stay null and are backfilled opportunistically the next time a crawl sees the
-- artifact again (see GithubArtifactProviderService.persistArtifact).
ALTER TABLE artifact
    ADD COLUMN IF NOT EXISTS author_login VARCHAR(39);

-- "What has this person contributed to this project": the lookup behind recognizing a hire's own
-- work in the repositories a project already has connected.
CREATE INDEX IF NOT EXISTS idx_artifact_author_login
    ON artifact (author_login)
    WHERE author_login IS NOT NULL;
