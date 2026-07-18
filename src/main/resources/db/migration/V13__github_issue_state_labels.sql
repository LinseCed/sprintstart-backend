-- Backend #9 prerequisite: forwards a GitHub issue's state (open/closed) and labels (e.g.
-- "good first issue") through to the AI service, closing a gap where GithubArtifactMapper fetched
-- both from GraphQL but dropped them before they ever reached an ingested artifact. Needed before
-- starter-work mining (ai#5) can reliably exclude closed issues. Canonical hand-maintained schema
-- delta; same ddl-auto caveat as prior migrations. Note: `artifact.metadata`/`artifact_projects`
-- predate this migration and were never added to the hand-maintained files either (pre-existing
-- drift, not introduced or fixed here).

ALTER TABLE artifact
    ADD COLUMN IF NOT EXISTS state VARCHAR(50);

CREATE TABLE IF NOT EXISTS artifact_labels (
    artifact_id UUID        NOT NULL,
    label       VARCHAR(255) NOT NULL,

    CONSTRAINT fk_artifact_labels_artifact
        FOREIGN KEY (artifact_id) REFERENCES artifact (id)
);

CREATE INDEX IF NOT EXISTS idx_artifact_labels_artifact_id
    ON artifact_labels(artifact_id);
