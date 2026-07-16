-- Phase 2 slice 2 (backend #7): minimal graph version tracking.
-- Single-row table: application code always reads the top row by version and
-- lazily creates the first one. No history/audit trail yet -- that's for a
-- later slice once there's a real way to edit the graph and classify changes.

CREATE TABLE IF NOT EXISTS competency_graph_version (
    id         UUID PRIMARY KEY,
    version    INTEGER   NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
