package com.sprintstart.sprintstartbackend.onboarding.model.response.verification

import com.sprintstart.sprintstartbackend.onboarding.external.enums.VerificationType
import java.util.UUID

/**
 * A graded check's config as shown via the API.
 *
 * `rubric`/`canonicalAnswer` are deliberately never included here -- revealing either would let a
 * learner read off the answer.
 *
 * Exactly one of [stepId] / [moduleId] is set: the check is owned either by a module (the shared
 * artifact) or by a legacy per-user step, which goes with the per-user tree (backend#53).
 */
data class VerificationResponse(
    val id: UUID,
    val stepId: UUID? = null,
    val moduleId: UUID? = null,
    val type: VerificationType,
    val prompt: String,
    val competencyKey: String,
    val level: String,
)
