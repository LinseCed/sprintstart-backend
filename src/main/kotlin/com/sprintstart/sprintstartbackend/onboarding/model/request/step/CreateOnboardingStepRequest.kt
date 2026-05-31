package com.sprintstart.sprintstartbackend.onboarding.model.request.step

import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepStatus
import java.util.UUID

data class CreateOnboardingStepRequest(
    val position: Int,
    val title: String,
    val description: String,
    val estimatedMinutes: Int,
    val status: StepStatus,
)
