package com.sprintstart.sprintstartbackend.ingestion.external.events

import java.util.UUID

/**
 * An ingestion run's artifacts have all reached the AI index and are searchable.
 *
 * ### Why this rather than `RunFinishedEvent`
 *
 * The design says generation is kicked off by "a crawl finishing". A crawl finishing is the wrong
 * moment: artifacts are embedded incrementally by the outbox drainer, so when `RunFinishedEvent`
 * fires the newest material is typically still queued. Generating there would retrieve against an
 * index that does not contain the crawl yet — and worse, would compute the *old* corpus fingerprint,
 * so the run would short-circuit as `unchanged` and generation would silently do nothing at all.
 *
 * The honest signal is "the corpus now contains this run", which is when the run's AI-sync roll-up
 * first reaches `SUCCEEDED`.
 *
 * ### Fires on the transition, not on every roll-up
 *
 * The roll-up recomputes after every drained batch. Publishing on each one would start a generation
 * run per batch. This fires only when a run *becomes* fully indexed.
 *
 * @param runId The ingestion run whose artifacts are now searchable.
 * @param projectIds Every project the run's artifacts belong to. Competencies are global, but a
 * module is written against one project's corpus, so the module pass needs to know which.
 */
data class RunIndexedEvent(
    val runId: UUID,
    val projectIds: Set<UUID>,
)
