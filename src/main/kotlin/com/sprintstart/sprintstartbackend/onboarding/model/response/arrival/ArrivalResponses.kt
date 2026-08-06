package com.sprintstart.sprintstartbackend.onboarding.model.response.arrival

import com.sprintstart.sprintstartbackend.onboarding.external.enums.Rigor
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/**
 * One arrival step as it applies to the caller.
 *
 * [settled], [settledAt] and [rigor] describe *this hire*; everything else is the shared
 * definition. An unsettled step carries nulls rather than being omitted — a hire needs to see what
 * is still outstanding, which is the entire point.
 */
@Schema(description = "One arrival step and whether the caller has settled it")
data class ArrivalStepResponse(
    @field:Schema(description = "Stable key this step is known by", example = "github-account")
    val key: String,
    @field:Schema(description = "Null for a company-wide step")
    val projectId: UUID?,
    /**
     * The project's name, for grouping the hire's list under a heading. Null for a company-wide
     * step, and also null on the **authoring** read — that caller named the scope in its own
     * request, so there is nothing to tell it that it did not already say.
     *
     * A hire's list is a union across every project they are on, so without this a person on two
     * projects sees *"Request staging access"* twice with nothing to distinguish the two. An id
     * cannot be a heading.
     */
    @field:Schema(description = "The project's name, for grouping; null for a company-wide step")
    val projectName: String?,
    val title: String,
    val description: String?,
    @field:Schema(description = "Where to go to do it, when there is such a place")
    val href: String?,
    val position: Int,
    @field:Schema(description = "How this step is settled: OBSERVED by the system, or DECLARED by the hire")
    val settledBy: Rigor,
    @field:Schema(description = "Whether the hire may settle this themselves, or only the system can")
    val selfConfirmable: Boolean,
    val settled: Boolean,
    val settledAt: Instant?,
    @field:Schema(description = "How this hire's step was actually established; null while unsettled")
    val rigor: Rigor?,
)

/**
 * A step the system knows how to check for itself, offered to whoever authors the list.
 *
 * A derivation is code, so a derived step cannot be written freely — it can only be one of these,
 * bound to its row by [key]. Offering them explicitly is what keeps that from being folklore about
 * which magic keys happen to work.
 */
@Schema(description = "An arrival step the system can verify by itself")
data class DerivableArrivalStepResponse(
    val key: String,
    @field:Schema(description = "Suggested wording; the author may change it after adding")
    val suggestedTitle: String,
    val suggestedDescription: String,
    @field:Schema(description = "Whether the hire may also settle it themselves")
    val selfConfirmable: Boolean,
    @field:Schema(description = "Whether this step has already been added to the list")
    val added: Boolean,
)

/**
 * The caller's arrival steps, with the outstanding work counted **by rigor and never blended**.
 *
 * ### Why there is no percentage here, and must never be
 *
 * The onboarding model this replaces reported a single `progressPercentage` that counted a step
 * somebody had ticked exactly the same as a check they had actually passed. That conflation is the
 * specific defect that made the old progress number meaningless, and it is trivial to reintroduce
 * by adding one convenient field to a response like this one.
 *
 * So the wire shape refuses to make it easy: there are counts per rigor and a count of what is
 * outstanding, and no total to divide by. A client that wants to say something honest says
 * *"5 confirmed by the system · 2 you told us about · 2 outstanding"*. `ArrivalControllerTest`
 * asserts that no field here is a blended completion figure.
 */
@Schema(description = "The caller's arrival steps, counted by how each was established")
data class MyArrivalResponse(
    val steps: List<ArrivalStepResponse>,
    @field:Schema(description = "Settled because the system observed it")
    val observedCount: Int,
    @field:Schema(description = "Settled because the hire said so")
    val declaredCount: Int,
    @field:Schema(description = "Not settled yet -- which is a normal day-one state, not an error")
    val outstandingCount: Int,
)
