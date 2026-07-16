package com.sprintstart.sprintstartbackend.onboarding.model.response.assessment

import java.util.UUID

data class StartAssessmentResponse(
    val sessionId: UUID,
    val question: String,
)
