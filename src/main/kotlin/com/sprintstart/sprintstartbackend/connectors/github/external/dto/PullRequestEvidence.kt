package com.sprintstart.sprintstartbackend.connectors.github.external.dto

/**
 * Real, observed state of one pull request, gathered on demand for artifact verification.
 *
 * [checksPassed] is `null` when GitHub reports no combined CI status (e.g. no checks configured
 * or still pending) -- only an explicit `SUCCESS`/`FAILURE`/`ERROR` rollup maps to `true`/`false`.
 */
data class PullRequestEvidence(
    val title: String,
    val body: String,
    val state: String,
    val filesChanged: List<String>,
    val checksPassed: Boolean?,
    val commitMessages: List<String>,
    /**
     * GitHub login of whoever opened the pull request, lower-cased, or `null` when GitHub reports
     * no author (e.g. a deleted account). Artifact verification compares this against the
     * submitting user's declared GitHub login -- without it, a hire could pass a task with
     * somebody else's pull request.
     */
    val authorLogin: String?,
)
