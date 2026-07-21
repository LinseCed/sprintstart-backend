package com.sprintstart.sprintstartbackend.onboarding.model.request.blueprint

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/**
 * A PM marking one competency as expected on a project's baseline, directly — no AI proposal round
 * trip. Every field is optional: a bare `PUT` means "expected here, at the competency's own bar,
 * required, not a mandate".
 */
data class SetBaselineEntryRequest(
    /**
     * The bar a hire must reach for this to count as met, or null to use the competency's own
     * target level. Range-validated here; the "must be 1..4" contract matches node editing.
     */
    @field:Min(1)
    @field:Max(4)
    val targetLevel: Int? = null,
    /** `required` or `recommended`; defaults to `required` when omitted (a PM marking it expected). */
    val requirement: String? = null,
    /** Mark as a protected mandate the AI may not drop and this API may not remove piecemeal. */
    val invariant: Boolean? = null,
)
