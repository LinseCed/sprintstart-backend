package com.sprintstart.sprintstartbackend.onboarding.model.request.buddy

import java.time.Instant
import java.util.UUID

/**
 * Pairs a hire with the person they can ask.
 *
 * [cadenceTargetDays] is how often the two are expected to speak. Contact frequency, not the mere
 * existence of a buddy, is what tracked with onboarding outcomes — so the target is a real setting
 * rather than a formality. Omitted, it falls back to the default.
 */
data class AssignBuddyRequest(
    val hireId: UUID,
    val buddyId: UUID,
    val cadenceTargetDays: Int? = null,
)

/**
 * Records that a conversation happened.
 *
 * [hireId] is only needed when a buddy logs on the hire's behalf; a hire logging their own contact
 * leaves it null. [occurredAt] allows recording a conversation after the fact, which is when people
 * actually remember to — but never in the future.
 *
 * [note] is for the pair. Nothing reads it.
 */
data class LogBuddyContactRequest(
    val hireId: UUID? = null,
    val occurredAt: Instant? = null,
    val note: String? = null,
)
