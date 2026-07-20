package com.sprintstart.sprintstartbackend.onboarding.model.response.metrics

import com.sprintstart.sprintstartbackend.onboarding.model.entity.EnvironmentEvidence
import java.time.Instant

/**
 * A hire's environment readiness on one project, as they see it.
 *
 * Not-ready is a real, reportable state — [ready] is false with everything else null — never an
 * error and never a locked screen. The point of the slice is that a hire is not gated out of the
 * product for failing a setup step; the next action lives in the UI, not in a 403.
 */
data class MyEnvironmentResponse(
    val ready: Boolean,
    /** When readiness was achieved; null while not ready. */
    val readyAt: Instant?,
    /** What settled it; null while not ready. `PULL_REQUEST` means it was derived, not reported. */
    val evidence: EnvironmentEvidence?,
    /** A human pointer to the evidence, when one was given. */
    val evidenceDetail: String?,
    /** True when readiness was inferred from ingested work rather than actively reported. */
    val derived: Boolean,
)
