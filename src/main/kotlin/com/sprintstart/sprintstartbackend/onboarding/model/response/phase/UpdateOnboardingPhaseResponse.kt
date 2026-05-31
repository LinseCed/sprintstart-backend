package com.sprintstart.sprintstartbackend.onboarding.model.response.phase

import com.sprintstart.sprintstartbackend.onboarding.model.response.step.GetOnboardingStepsResponse
import java.util.UUID

data class UpdateOnboardingPhaseResponse(
    val id: UUID,
    val phaseId: UUID,
    val title: String,
    val description: String,
    val step: List<GetOnboardingStepsResponse>,
)
