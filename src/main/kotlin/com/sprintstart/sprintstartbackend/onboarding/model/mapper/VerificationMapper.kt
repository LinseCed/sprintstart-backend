package com.sprintstart.sprintstartbackend.onboarding.model.mapper

import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Verification
import com.sprintstart.sprintstartbackend.onboarding.model.entity.VerificationAttempt
import com.sprintstart.sprintstartbackend.onboarding.model.response.verification.SubmitVerificationAttemptResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.verification.VerificationResponse

fun Verification.toResponse(): VerificationResponse =
    VerificationResponse(
        id = id,
        stepId = stepId,
        moduleId = moduleId,
        type = type,
        prompt = prompt,
        competencyKey = competencyKey,
        level = level,
    )

fun VerificationAttempt.toSubmitResponse(stepStatus: StepStatus? = null): SubmitVerificationAttemptResponse =
    SubmitVerificationAttemptResponse(
        attemptId = id,
        stepId = verification.stepId,
        moduleId = verification.moduleId,
        passed = passed,
        score = score,
        feedback = feedback,
        hint = hint,
        attemptNo = attemptNo,
        graphVersion = graphVersion,
        stepStatus = stepStatus,
    )
