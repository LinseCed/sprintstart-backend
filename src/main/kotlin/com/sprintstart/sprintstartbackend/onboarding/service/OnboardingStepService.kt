package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingPhase
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingStep
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toCreateResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toGetAllResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toGetResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toUpdateResponse
import com.sprintstart.sprintstartbackend.onboarding.model.request.step.CreateOnboardingStepRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.step.UpdateOnboardingStepRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.CreateOnboardingStepResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.GetOnboardingStepResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.GetOnboardingStepsResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.UpdateOnboardingStepResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingPhaseRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingStepRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID
import kotlin.ranges.contains

/**
 * Manages onboarding steps within a phase.
 *
 * Steps are ordered siblings under a phase. Position changes trigger sibling reordering,
 * and status updates also maintain completion metadata such as `completedAt` and `skipReason`.
 */
@Suppress("TooManyFunctions")
@Service
class OnboardingStepService(
    private val onboardingPhaseRepository: OnboardingPhaseRepository,
    private val onboardingStepRepository: OnboardingStepRepository,
    private val userApi: UserApi,
) {
//  ========================== Methods for users ==========================

    /**
     * Returns all steps for one phase in the authenticated user's onboarding path.
     *
     * @param authId External authentication identifier.
     * @param phaseId Identifier of the parent phase.
     * @return Ordered steps for the phase.
     * @throws ResponseStatusException When the user does not exist.
     */
    @Transactional(readOnly = true)
    fun getOnboardingStepsForMe(authId: String, phaseId: UUID): List<GetOnboardingStepsResponse> {
        val userId = userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        return onboardingStepRepository
            .findAllByPhaseIdAndPhasePathUserId(phaseId, userId)
            .map { it.toGetAllResponse() }
    }

    /**
     * Creates a step in one phase of the authenticated user's onboarding path.
     *
     * Existing steps at or after the requested position are shifted right to make room.
     * New steps always start with [StepStatus.WAITING].
     *
     * @param authId External authentication identifier.
     * @param phaseId Identifier of the parent phase.
     * @param request Step creation payload.
     * @return The created step.
     * @throws ResponseStatusException When the user, phase, or requested position is invalid.
     */
    @Transactional
    fun createOnboardingStepForMe(
        authId: String,
        phaseId: UUID,
        request: CreateOnboardingStepRequest,
    ): CreateOnboardingStepResponse {
        val userId = userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        val phase = onboardingPhaseRepository
            .findByIdAndPathUserId(phaseId, userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No phase found with id: $phaseId") }

        // Shift right
        shiftStepsRight(phase, request)

        val onboardingStep = OnboardingStep(
            phase = phase,
            position = request.position,
            title = request.title,
            description = request.description,
            type = request.type,
            estimatedMinutes = request.estimatedMinutes,
            expectedOutcome = request.expectedOutcome,
            status = StepStatus.WAITING,
        )

        return onboardingStepRepository.save(onboardingStep).toCreateResponse()
    }

    /**
     * Returns one step from the authenticated user's onboarding path.
     *
     * @param authId External authentication identifier.
     * @param stepId Identifier of the step to load.
     * @return The requested step.
     * @throws ResponseStatusException When the user or step does not exist.
     */
    @Transactional(readOnly = true)
    fun getOnboardingStepForMe(authId: String, stepId: UUID): GetOnboardingStepResponse {
        val userId = userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        return onboardingStepRepository
            .findByIdAndPhasePathUserId(stepId, userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No step found with id: $stepId") }
            .toGetResponse()
    }

    /**
     * Updates a step in the authenticated user's onboarding path.
     *
     * Position changes reorder sibling steps. Status changes also update completion metadata:
     * finished and skipped steps receive a completion timestamp, while waiting steps clear it.
     *
     * @param authId External authentication identifier.
     * @param stepId Identifier of the step to update.
     * @param request Step update payload.
     * @return The updated step.
     * @throws ResponseStatusException When the user, step, or requested position is invalid.
     */
    @Transactional
    fun updateOnboardingStepForMe(
        authId: String,
        stepId: UUID,
        request: UpdateOnboardingStepRequest,
    ): UpdateOnboardingStepResponse {
        val userId = userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        val step = onboardingStepRepository
            .findByIdAndPhasePathUserId(stepId, userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No step found with id: $stepId") }

        shiftStepsBetween(step, request)

        step.position = request.position
        step.title = request.title
        step.description = request.description
        step.type = request.type
        step.estimatedMinutes = request.estimatedMinutes
        step.expectedOutcome = request.expectedOutcome

        updateStatus(step, request)

        step.status = request.status

        return step.toUpdateResponse()
    }

    /**
     * Deletes a step from the authenticated user's onboarding path.
     *
     * Steps positioned after the deleted step are shifted left by one.
     *
     * @param authId External authentication identifier.
     * @param stepId Identifier of the step to delete.
     * @throws ResponseStatusException When the user or step does not exist.
     */
    @Transactional
    fun deleteOnboardingStepForMe(authId: String, stepId: UUID) {
        val userId = userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        val step = onboardingStepRepository
            .findByIdAndPhasePathUserId(stepId, userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No step found") }

        val stepsToShift = onboardingStepRepository
            .findAllByPhaseIdAndPositionGreaterThan(step.phase.id, step.position)
        stepsToShift.forEach { it.position -= 1 }

        onboardingStepRepository.delete(step)
    }

//  ========================== Methods for admins ==========================

    /**
     * Returns all steps belonging to the specified phase.
     *
     * @param phaseId The ID of the phase whose steps should be retrieved.
     * @return A list of steps for the given phase.
     */
    @Transactional(readOnly = true)
    fun getOnboardingStepsByPhaseId(phaseId: UUID): List<GetOnboardingStepResponse> {
        return onboardingStepRepository
            .findAllByPhaseId(phaseId)
            .map { it.toGetResponse() }
    }

    /**
     * Creates a step in the specified phase.
     *
     * Existing steps at or after the requested position are shifted right to make room.
     * New steps always start with [StepStatus.WAITING].
     *
     * @param phaseId Identifier of the parent phase.
     * @param request Step creation payload.
     * @return The created step.
     * @throws ResponseStatusException When the phase does not exist or the position is invalid.
     */
    @Transactional
    fun createOnboardingStepForPhaseId(
        phaseId: UUID,
        request: CreateOnboardingStepRequest,
    ): CreateOnboardingStepResponse {
        val phase = onboardingPhaseRepository
            .findById(phaseId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No phase found with id: $phaseId") }

        // Shift right
        shiftStepsRight(phase, request)

        val onboardingStep = OnboardingStep(
            phase = phase,
            position = request.position,
            title = request.title,
            description = request.description,
            type = request.type,
            estimatedMinutes = request.estimatedMinutes,
            expectedOutcome = request.expectedOutcome,
            status = StepStatus.WAITING,
        )

        return onboardingStep.toCreateResponse()
    }

    /**
     * Returns one onboarding step by ID.
     *
     * @param stepId The ID of the onboarding step.
     * @return The onboarding step response.
     * @throws ResponseStatusException with [HttpStatus.NOT_FOUND] if no step exists with [stepId].
     */
    @Transactional(readOnly = true)
    fun getOnboardingStepById(stepId: UUID): GetOnboardingStepResponse {
        return onboardingStepRepository
            .findById(stepId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No step found with id: $stepId") }
            .toGetResponse()
    }

    /**
     * Updates a step by ID.
     *
     * Position changes reorder sibling steps. Status changes also update completion metadata:
     * finished and skipped steps receive a completion timestamp, while waiting steps clear it.
     *
     * @param stepId Identifier of the step to update.
     * @param request Step update payload.
     * @return The updated step.
     * @throws ResponseStatusException When the step does not exist or the position is invalid.
     */
    @Transactional
    fun updateOnboardingStepById(stepId: UUID, request: UpdateOnboardingStepRequest): UpdateOnboardingStepResponse {
        val step = onboardingStepRepository
            .findById(stepId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No step found with id: $stepId") }

        shiftStepsBetween(step, request)

        step.position = request.position
        step.title = request.title
        step.description = request.description
        step.type = request.type
        step.estimatedMinutes = request.estimatedMinutes
        step.expectedOutcome = request.expectedOutcome

        updateStatus(step, request)

        step.status = request.status

        return step.toUpdateResponse()
    }

    /**
     * Deletes an onboarding step and shifts subsequent sibling steps one position back.
     *
     * @param stepId The ID of the step to delete.
     * @throws ResponseStatusException with [HttpStatus.NOT_FOUND] if no step exists with [stepId].
     */
    @Transactional
    fun deleteOnboardingStepById(stepId: UUID) {
        val step = onboardingStepRepository
            .findById(stepId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No step found with id: $stepId") }

        // Shift left
        val stepsToShift = onboardingStepRepository
            .findAllByPhaseIdAndPositionGreaterThan(step.phase.id, step.position)
        stepsToShift.forEach { it.position -= 1 }

        onboardingStepRepository.delete(step)
    }

//  ========================== Helper Methods ==========================

    private fun shiftStepsRight(
        phase: OnboardingPhase,
        request: CreateOnboardingStepRequest,
    ) {
        val stepCount = onboardingStepRepository.countByPhaseId(phase.id)

        if (request.position !in 0..stepCount) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Position must be between 0 and $stepCount",
            )
        }

        val stepsToShift = onboardingStepRepository
            .findByPhaseIdAndPositionGreaterThanEqualOrderByPositionDesc(
                phase.id,
                request.position,
            )

        stepsToShift.forEach { it.position += 1 }
    }

    private fun shiftStepsBetween(
        step: OnboardingStep,
        request: UpdateOnboardingStepRequest,
    ) {
        val stepCount = onboardingStepRepository.countByPhaseId(step.phase.id)

        if (request.position !in 0 until stepCount) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Position must be between 0 and ${stepCount - 1}")
        }

        val oldPosition = step.position
        val newPosition = request.position

        if (oldPosition < newPosition) {
            val stepsToShift = onboardingStepRepository
                .findByPhaseIdAndPositionBetween(
                    step.phase.id,
                    oldPosition + 1,
                    newPosition,
                )

            stepsToShift.forEach { it.position -= 1 }
        }

        if (oldPosition > newPosition) {
            val stepsToShift = onboardingStepRepository
                .findByPhaseIdAndPositionBetween(
                    step.phase.id,
                    newPosition,
                    oldPosition - 1,
                )

            stepsToShift.forEach { it.position += 1 }
        }
    }

    private fun updateStatus(
        step: OnboardingStep,
        request: UpdateOnboardingStepRequest,
    ) {
        if (step.status != request.status) {
            when (request.status) {
                StepStatus.FINISHED -> {
                    step.completedAt = Instant.now()
                    step.skipReason = null
                }

                StepStatus.SKIPPED -> {
                    step.completedAt = Instant.now()
                    step.skipReason = request.skipReason ?: "No reason given"
                }

                StepStatus.WAITING -> {
                    step.completedAt = null
                    step.skipReason = ""
                }
            }
        }
    }
}
