package com.sprintstart.sprintstartbackend.onboarding.model.mapper

import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepPageKind
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingStep
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.CreateOnboardingStepResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.GetOnboardingStepResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.GetOnboardingStepsResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.StepPageResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.UpdateOnboardingStepResponse

/**
 * Derives the step's ordered stepper pages from its existing shape: a `LESSON` page when a
 * grounded lesson body is present, a `TASK` page when hands-on tasks exist, and a `VERIFY` page
 * when the step has a configured verification ([hasVerification], resolved by the caller since the
 * verification lives in a separate repository). `TASK` and `VERIFY` are markers — their detail is
 * already on the step (`tasks`) or fetched via the verification-attempt endpoints.
 *
 * @param hasVerification Whether a `Verification` is configured for this step.
 */
fun OnboardingStep.derivePages(hasVerification: Boolean): List<StepPageResponse> {
    val pages = mutableListOf<StepPageResponse>()
    this.content?.takeIf { it.isNotBlank() }?.let { lesson ->
        pages += StepPageResponse(kind = StepPageKind.LESSON, title = "Learn", content = lesson)
    }
    if (this.tasks.isNotEmpty()) {
        pages += StepPageResponse(kind = StepPageKind.TASK, title = "Practice")
    }
    if (hasVerification) {
        pages += StepPageResponse(kind = StepPageKind.VERIFY, title = "Verify")
    }
    return pages
}

fun OnboardingStep.toGetAllResponse(): GetOnboardingStepsResponse {
    return GetOnboardingStepsResponse(
        id = this.id,
        phaseId = this.phase.id,
        position = this.position,
        title = this.title,
        description = this.description,
        type = this.type,
        estimatedMinutes = this.estimatedMinutes,
        expectedOutcomes = listOf(this.expectedOutcome),
        status = this.status,
        startedAt = this.startedAt,
        completedAt = this.completedAt,
        feedback = this.feedback.lastOrNull()?.toGetResponse(),
        skip = this.skips.lastOrNull()?.toStepResponse(),
    )
}

fun OnboardingStep.toGetResponse(hasVerification: Boolean = false): GetOnboardingStepResponse {
    return GetOnboardingStepResponse(
        id = this.id,
        phaseId = this.phase.id,
        position = this.position,
        title = this.title,
        description = this.description,
        estimatedMinutes = this.estimatedMinutes,
        type = this.type,
        expectedOutcomes = listOf(this.expectedOutcome),
        tasks = this.tasks.map { task -> task.toGetAllResponse() },
        resources = this.resources.map { resource -> resource.toGetAllResponse() },
        status = this.status,
        startedAt = this.startedAt,
        completedAt = this.completedAt,
        feedback = this.feedback.lastOrNull()?.toGetResponse(),
        skip = this.skips.lastOrNull()?.toStepResponse(),
        pages = this.derivePages(hasVerification),
    )
}

fun OnboardingStep.toCreateResponse(): CreateOnboardingStepResponse {
    return CreateOnboardingStepResponse(
        id = this.id,
        phaseId = this.phase.id,
        position = this.position,
        title = this.title,
        description = this.description,
        type = this.type,
        estimatedMinutes = this.estimatedMinutes,
        expectedOutcome = this.expectedOutcome,
        status = this.status,
    )
}

fun OnboardingStep.toUpdateResponse(): UpdateOnboardingStepResponse {
    return UpdateOnboardingStepResponse(
        id = this.id,
        phaseId = this.phase.id,
        position = this.position,
        title = this.title,
        description = this.description,
        estimatedMinutes = this.estimatedMinutes,
        expectedOutcome = this.expectedOutcome,
        status = this.status,
        startedAt = this.startedAt,
        completedAt = this.completedAt,
        feedback = this.feedback.lastOrNull()?.toGetResponse(),
        skip = this.skips.lastOrNull()?.toStepResponse(),
    )
}
