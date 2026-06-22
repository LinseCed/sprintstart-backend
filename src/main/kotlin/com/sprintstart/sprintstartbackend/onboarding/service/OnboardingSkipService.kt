package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.SkipStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingSkip
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingStep
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toCreateResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toGetAllResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toGetResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toReviewResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toUpdateResponse
import com.sprintstart.sprintstartbackend.onboarding.model.request.skip.CreateOnboardingSkipRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.skip.ReviewOnboardingSkipRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.skip.UpdateOnboardingSkipRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.skip.CreateOnboardingSkipResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.skip.GetAllOnboardingSkipsResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.skip.GetOnboardingSkipResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.skip.ReviewOnboardingSkipResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.UpdateOnboardingStepResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingSkipRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingStepRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * Manages skip requests for onboarding steps.
 *
 * A pending skip request does not complete the step on its own. The step remains
 * waiting until an admin accepts or denies the request. Accepted skips mark the
 * step as skipped and completed; denied skips return it to waiting.
 */
@Service
class OnboardingSkipService(
    private val onboardingSkipRepository: OnboardingSkipRepository,
    private val onboardingStepRepository: OnboardingStepRepository,
    private val userApi: UserApi,
) {
//  ========================== Methods for users ==========================

    @Transactional(readOnly = true)
    fun getAllSkipsForMe(authId: String): List<GetOnboardingSkipResponse> {
        val userId = getUserId(authId)

        return onboardingSkipRepository
            .findAllByStepPhasePathUserIdOrderByCreatedAtAsc(userId)
            .map { it.toGetResponse() }
    }

    @Transactional(readOnly = true)
    fun getSkipsByStepIdForMe(authId: String, stepId: UUID): List<GetOnboardingSkipResponse> {
        val userId = getUserId(authId)
        ensureUserOwnsStep(userId, stepId)

        return onboardingSkipRepository
            .findAllByStepIdAndStepPhasePathUserIdOrderByCreatedAtAsc(stepId, userId)
            .map { it.toGetResponse() }
    }

    @Transactional(readOnly = true)
    fun getSkipByIdForMe(authId: String, skipId: UUID): GetOnboardingSkipResponse {
        val userId = getUserId(authId)

        return onboardingSkipRepository
            .findByIdAndStepPhasePathUserId(skipId, userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No skip found with id: $skipId") }
            .toGetResponse()
    }

    @Transactional
    fun createOnboardingSkipForMe(
        authId: String,
        stepId: UUID,
        request: CreateOnboardingSkipRequest,
    ): CreateOnboardingSkipResponse {
        val userId = getUserId(authId)
        val step = ensureUserOwnsStep(userId, stepId)

        ensureStepCanReceivePendingSkip(step)

        val onboardingSkip = OnboardingSkip(
            step = step,
            reason = request.reason,
        )
        step.skips += onboardingSkip

        return onboardingSkipRepository.save(onboardingSkip).toCreateResponse()
    }

    @Transactional
    fun updateOnboardingSkipForMe(
        authId: String,
        skipId: UUID,
        request: UpdateOnboardingSkipRequest,
    ): UpdateOnboardingStepResponse {
        val userId = getUserId(authId)
        val skip = onboardingSkipRepository
            .findByIdAndStepPhasePathUserId(skipId, userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No skip found with id: $skipId") }

        ensurePending(skip)

        skip.reason = request.reason

        return skip.step.toUpdateResponse()
    }

    @Transactional
    fun deleteSkipByIdForMe(authId: String, skipId: UUID) {
        val userId = getUserId(authId)
        val skip = onboardingSkipRepository
            .findByIdAndStepPhasePathUserId(skipId, userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No skip found with id: $skipId") }

        deleteSkip(skip)
    }

//  ========================== Methods for admins ==========================

    @Transactional(readOnly = true)
    fun getAllSkips(): List<GetAllOnboardingSkipsResponse> {
        return onboardingSkipRepository.findAllByOrderByCreatedAtAsc().map { it.toGetAllResponse() }
    }

    @Transactional(readOnly = true)
    fun getAllSkipsByUserId(userId: UUID): List<GetOnboardingSkipResponse> {
        if (!userApi.exists(userId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found with id: $userId")
        }

        return onboardingSkipRepository
            .findAllByStepPhasePathUserIdOrderByCreatedAtAsc(userId)
            .map { it.toGetResponse() }
    }

    @Transactional(readOnly = true)
    fun getAllSkipsByStepId(stepId: UUID): List<GetOnboardingSkipResponse> {
        if (!onboardingStepRepository.existsById(stepId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No step found with id: $stepId")
        }

        return onboardingSkipRepository
            .findAllByStepIdOrderByCreatedAtAsc(stepId)
            .map { it.toGetResponse() }
    }

    @Transactional(readOnly = true)
    fun getSkipById(skipId: UUID): GetOnboardingSkipResponse {
        return onboardingSkipRepository
            .findById(skipId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No skip found with id: $skipId") }
            .toGetResponse()
    }

    @Transactional
    fun acceptSkipById(skipId: UUID, request: ReviewOnboardingSkipRequest): ReviewOnboardingSkipResponse {
        val skip = onboardingSkipRepository
            .findById(skipId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No skip found with id: $skipId") }

        ensurePending(skip)

        val reviewedAt = Instant.now()
        skip.status = SkipStatus.ACCEPTED
        skip.reviewComment = request.reviewCommend
        skip.resolvedAt = reviewedAt
        skip.step.status = StepStatus.SKIPPED
        skip.step.completedAt = reviewedAt

        return skip.toReviewResponse()
    }

    @Transactional
    fun denySkipById(skipId: UUID, request: ReviewOnboardingSkipRequest): ReviewOnboardingSkipResponse {
        val skip = onboardingSkipRepository
            .findById(skipId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No skip found with id: $skipId") }

        ensurePending(skip)

        val reviewedAt = Instant.now()
        skip.status = SkipStatus.DENIED
        skip.reviewComment = request.reviewCommend
        skip.resolvedAt = reviewedAt
        skip.step.status = StepStatus.WAITING
        skip.step.completedAt = null

        return skip.toReviewResponse()
    }

    @Transactional
    fun deleteSkipById(skipId: UUID) {
        val skip = onboardingSkipRepository
            .findById(skipId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No skip found with id: $skipId") }

        deleteSkip(skip)
    }

//  ========================== Helper methods ==========================

    private fun getUserId(authId: String): UUID {
        return userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }
    }

    private fun ensureUserOwnsStep(userId: UUID, stepId: UUID): OnboardingStep {
        return onboardingStepRepository
            .findByIdAndPhasePathUserId(stepId, userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No step found with id: $stepId") }
    }

    private fun ensureStepCanReceivePendingSkip(step: OnboardingStep) {
        if (step.status != StepStatus.WAITING) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Only waiting steps can receive a skip request",
            )
        }

        if (step.skips.lastOrNull()?.status == SkipStatus.PENDING) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Step ${step.id} already has a pending skip request",
            )
        }
    }

    private fun ensurePending(skip: OnboardingSkip) {
        if (skip.status != SkipStatus.PENDING) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Only pending skips can be modified",
            )
        }
    }

    private fun deleteSkip(skip: OnboardingSkip) {
        val step = skip.step
        step.skips.removeIf { existingSkip -> existingSkip.id == skip.id }
    }
}
