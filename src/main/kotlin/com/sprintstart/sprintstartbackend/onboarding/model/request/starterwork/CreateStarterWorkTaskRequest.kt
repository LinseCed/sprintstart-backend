package com.sprintstart.sprintstartbackend.onboarding.model.request.starterwork

/**
 * A PM's hand-authored starter-work task, created without any AI mining.
 *
 * The origination counterpart to approving a mined proposal: mining over ingested GitHub issues is
 * one way to fill the contribution pool, this is the other, for a task the corpus never surfaced.
 * A hand-authored task is born `APPROVED` and materialises its `CONTRIBUTION` node immediately -- a
 * PM authoring a task *is* the review, so there is nothing to approve back to them, matching direct
 * baseline authoring.
 *
 * It has no ingested source, so it carries no `sourceId` from the client: the service synthesises a
 * stable one. [sourceUrl] is an optional human-facing link (e.g. to the issue or PR the task
 * tracks); [competencyKeys] are the prerequisites wired as edges into the node, and a key that is
 * not a live competency is skipped rather than rejecting the whole task -- the tags are enrichment.
 */
data class CreateStarterWorkTaskRequest(
    val title: String,
    val summary: String? = null,
    val sourceUrl: String? = null,
    val competencyKeys: List<String> = emptyList(),
)
