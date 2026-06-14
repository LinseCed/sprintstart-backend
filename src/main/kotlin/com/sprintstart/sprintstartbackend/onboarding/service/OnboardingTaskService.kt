package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingStep
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingTask
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toCreateResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toGetAllResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toGetResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toUpdateResponse
import com.sprintstart.sprintstartbackend.onboarding.model.request.task.CreateOnboardingTaskRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.task.UpdateOnboardingTaskRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.task.CreateOnboardingTaskResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.task.GetOnboardingTaskResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.task.GetOnboardingTasksResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.task.UpdateOnboardingTaskResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingStepRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingTaskRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import kotlin.collections.forEach
import kotlin.ranges.contains

@Service
class OnboardingTaskService(
    private val onboardingTaskRepository: OnboardingTaskRepository,
    private val onboardingStepRepository: OnboardingStepRepository,
    private val userApi: UserApi,
) {
//  ========================== Methods for users ==========================

    @Transactional
    fun getOnboardingTasksForMe(authId: String, stepId: UUID): List<GetOnboardingTasksResponse> {
        val userId = userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        return onboardingTaskRepository
            .findAllByStepIdAndStepPhasePathUserId(stepId, userId)
            .map { it.toGetAllResponse() }
    }

    @Transactional
    fun createOnboardingTaskForMe(
        authId: String,
        stepId: UUID,
        request: CreateOnboardingTaskRequest,
    ): CreateOnboardingTaskResponse {
        val userId = userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }
// Todo: think about replacing this with HttpStatus.CONFLICT

        val step = onboardingStepRepository
            .findByIdAndPhasePathUserId(stepId, userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Step not found") }

        shiftTasksRight(step, request)

        val onboardingTask = OnboardingTask(
            step = step,
            position = request.position,
            title = request.title,
            description = request.description,
        )

        return onboardingTaskRepository.save(onboardingTask).toCreateResponse()
    }

    @Transactional
    fun getOnboardingTaskForMe(authId: String, taskId: UUID): GetOnboardingTaskResponse {
        val userId = userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        return onboardingTaskRepository
            .findByIdAndStepPhasePathUserId(taskId, userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found") }
            .toGetResponse()
    }

    @Transactional
    fun updateOnboardingTaskForMe(
        authId: String,
        taskId: UUID,
        request: UpdateOnboardingTaskRequest,
    ): UpdateOnboardingTaskResponse {
        val userId = userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        val task = onboardingTaskRepository
            .findByIdAndStepPhasePathUserId(taskId, userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Step not found") }

        shiftTasksBetween(task, request)

        task.position = request.position
        task.title = request.title
        task.description = request.description
        task.finished = request.finished

        return task.toUpdateResponse()
    }

    @Transactional
    fun deleteOnboardingTaskForMe(authId: String, taskId: UUID) {
        val userId = userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        val task = onboardingTaskRepository
            .findByIdAndStepPhasePathUserId(taskId, userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Step not found") }

        onboardingTaskRepository.delete(task)
    }

//  ========================== Methods for admins ==========================

    /**
     * Retrieves all onboarding tasks belonging to the specified step.
     *
     * @param stepId The ID of the step whose tasks should be retrieved.
     * @return A list of tasks for the given step.
     */
    @Transactional(readOnly = true)
    fun getOnboardingTasksByStepId(stepId: UUID): List<GetOnboardingTaskResponse> {
        return onboardingTaskRepository
            .findAllByStepId(stepId)
            .map { it.toGetResponse() }
    }

    /**
     * Creates a new onboarding task within the specified step at the requested position.
     *
     * All existing tasks at or after [CreateOnboardingTaskRequest.position] are shifted
     * one position forward.
     *
     * @param stepId The ID of the step to add the task to.
     * @param request The task creation request.
     * @return The created task response.
     * @throws ResponseStatusException with [HttpStatus.NOT_FOUND] if no step exists with [stepId].
     */
    @Transactional
    fun createOnboardingTaskForStepId(
        stepId: UUID,
        request: CreateOnboardingTaskRequest,
    ): CreateOnboardingTaskResponse {
        val step = onboardingStepRepository
            .findById(stepId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No step found with id: $stepId") }

        shiftTasksRight(step, request)

        val onboardingTask = OnboardingTask(
            step = step,
            position = request.position,
            title = request.title,
            description = request.description,
        )

        return onboardingTaskRepository.save(onboardingTask).toCreateResponse()
    }

    /**
     * Retrieves a single onboarding task by its ID.
     *
     * @param taskId The ID of the onboarding task.
     * @return The onboarding task response.
     * @throws ResponseStatusException with [HttpStatus.NOT_FOUND] if no task exists with [taskId].
     */
    @Transactional(readOnly = true)
    fun getOnboardingTaskById(taskId: UUID): GetOnboardingTaskResponse {
        return onboardingTaskRepository
            .findById(taskId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No task found with id: $taskId") }
            .toGetResponse()
    }

    /**
     * Updates an existing onboarding task, including repositioning it within its step.
     *
     * When the position changes, sibling tasks between the old and new positions are shifted
     * to maintain contiguous ordering.
     *
     * @param taskId The ID of the task to update.
     * @param request The update request.
     * @return The updated task response.
     * @throws ResponseStatusException with [HttpStatus.NOT_FOUND] if no task exists with [taskId].
     */
    @Transactional
    fun updateOnboardingTaskById(taskId: UUID, request: UpdateOnboardingTaskRequest): UpdateOnboardingTaskResponse {
        val task = onboardingTaskRepository
            .findById(taskId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No task found with id: $taskId") }

        shiftTasksBetween(task, request)

        task.position = request.position
        task.title = request.title
        task.description = request.description
        task.finished = request.finished

        return onboardingTaskRepository.save(task).toUpdateResponse()
    }

    /**
     * Deletes an onboarding task and shifts subsequent sibling tasks one position back.
     *
     * @param taskId The ID of the task to delete.
     * @throws ResponseStatusException with [HttpStatus.NOT_FOUND] if no task exists with [taskId].
     */
    @Transactional
    fun deleteOnboardingTaskById(taskId: UUID) {
        val task = onboardingTaskRepository
            .findById(taskId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No task found with id: $taskId") }

        val tasksToShift = onboardingTaskRepository
            .findAllByStepIdAndPositionGreaterThan(task.step.id, task.position)
        tasksToShift.forEach { it.position -= 1 }

        onboardingTaskRepository.delete(task)
    }

//  ========================== Helper Methods ==========================

    private fun shiftTasksRight(
        step: OnboardingStep,
        request: CreateOnboardingTaskRequest,
    ) {
        val taskCount = onboardingTaskRepository.countByStepId(step.id)

        if (request.position !in 0..taskCount) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Position must be between 0 and $taskCount",
            )
        }

        val tasksToShift = onboardingTaskRepository
            .findByStepIdAndPositionGreaterThanEqualOrderByPositionDesc(
                step.id,
                request.position,
            )

        tasksToShift.forEach { it.position += 1 }
    }

    private fun shiftTasksBetween(
        task: OnboardingTask,
        request: UpdateOnboardingTaskRequest,
    ) {
        val taskCount = onboardingTaskRepository.countByStepId(task.step.id)

        if (request.position !in 0 until taskCount) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Position must be between 0 and ${taskCount - 1}")
        }

        val oldPosition = task.position
        val newPosition = request.position

        if (oldPosition < newPosition) {
            val stepsToShift = onboardingTaskRepository
                .findByStepIdAndPositionBetween(
                    task.step.id,
                    oldPosition + 1,
                    newPosition,
                )

            stepsToShift.forEach { it.position -= 1 }
        }

        if (oldPosition > newPosition) {
            val stepsToShift = onboardingTaskRepository
                .findByStepIdAndPositionBetween(
                    task.step.id,
                    newPosition,
                    oldPosition - 1,
                )

            stepsToShift.forEach { it.position += 1 }
        }
    }
}

// TODO: Add doc
