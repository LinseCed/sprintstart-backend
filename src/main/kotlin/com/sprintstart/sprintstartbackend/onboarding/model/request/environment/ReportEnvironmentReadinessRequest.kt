package com.sprintstart.sprintstartbackend.onboarding.model.request.environment

import com.sprintstart.sprintstartbackend.onboarding.model.entity.EnvironmentEvidence

/**
 * What the documented command posts from the hire's machine when the environment comes up.
 *
 * [evidence] must be [EnvironmentEvidence.BUILD_TEST_RUN] or [EnvironmentEvidence.GREEN_CI];
 * `PULL_REQUEST` is derived from ingested work, never reported, and is rejected here.
 *
 * [readyAt] lets the command report when the run actually passed; omitted, the server uses now.
 * [detail] is an optional pointer — a CI run URL, a one-line build summary.
 */
data class ReportEnvironmentReadinessRequest(
    val evidence: EnvironmentEvidence,
    val readyAt: java.time.Instant? = null,
    val detail: String? = null,
)
