package com.sprintstart.sprintstartbackend.onboarding.model.response.setup

import java.util.UUID

/**
 * Whether a project is ready to onboard someone, as a ladder of setup stages.
 *
 * Derived on read from what each stage's own surface already stores -- there is no readiness table
 * to fall behind, and a stage completed before this shipped still reads done. Composed, not gated:
 * per the "retire the gates" decision, a PM can proceed at any state; the rungs are a nudge.
 *
 * The corpus stage is deliberately **absent** here. Corpus health belongs to the ingestion module
 * (its own `IngestionStatusController`), and this endpoint stays inside the onboarding module rather
 * than reaching across the boundary to re-derive it. The client composes the corpus rung onto the
 * top of this ladder from the ingestion status it already fetches.
 */
data class SetupReadinessResponse(
    val projectId: UUID,
    val rungs: List<SetupRungResponse>,
    /** True when every rung this endpoint owns is [RungState.OK]. A summary of the rungs, not a lock. */
    val ready: Boolean,
)

data class SetupRungResponse(
    /** Stable key the client renders off: `skill-map`, `baseline`, `starter-tasks`, `human-loop`. */
    val key: String,
    val state: RungState,
    /** The positive quantity for this rung (approved competencies, baseline entries, ...) -- never a pending count. */
    val count: Int,
    /** One sentence a PM can act on: what is missing, or what is waiting on them. */
    val detail: String,
)

/**
 * [OK] nothing to do here. [WARN] something is missing or waiting on the PM, but they can proceed.
 * [BLOCKED] this rung cannot be acted on until an earlier one is done -- a baseline has nothing to
 * select from until competencies are approved. No state gates onboarding.
 */
enum class RungState { OK, WARN, BLOCKED }
