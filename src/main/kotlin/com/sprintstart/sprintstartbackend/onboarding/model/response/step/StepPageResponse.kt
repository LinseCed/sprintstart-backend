package com.sprintstart.sprintstartbackend.onboarding.model.response.step

import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepPageKind

/**
 * One page of a learn-verify step/module, in render order.
 *
 * The client renders a stepper from the step's [GetOnboardingStepResponse.pages]. A `LESSON` page
 * carries its lesson body in [content]; `TASK` and `VERIFY` pages are markers whose detail the
 * client already has (the step's `tasks`, and the step's verification via the existing
 * verification-attempt endpoints), so their [content] is null.
 */
data class StepPageResponse(
    val kind: StepPageKind,
    val title: String,
    val content: String? = null,
)
