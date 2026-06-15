package com.sprintstart.sprintstartbackend.onboarding.controller

import com.sprintstart.sprintstartbackend.onboarding.model.request.task.CreateOnboardingTaskRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.task.UpdateOnboardingTaskRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.task.CreateOnboardingTaskResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.task.GetOnboardingTaskResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.task.GetOnboardingTasksResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.task.UpdateOnboardingTaskResponse
import com.sprintstart.sprintstartbackend.onboarding.service.OnboardingTaskService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST controller for managing onboarding tasks.
 *
 * Exposes endpoints under `/api/v1/onboarding` for creating, retrieving, updating,
 * and deleting onboarding tasks. Tasks are ordered checklist items sitting directly
 * below a step. They are leaf nodes in the onboarding hierarchy and have no children.
 *
 * Tasks are ordered within their parent step by a numeric position. Insertions, updates,
 * and deletions automatically shift sibling tasks to maintain a contiguous, gap-free ordering.
 * The `finished` flag can be toggled freely without any additional side effects.
 *
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@Tag(name = "Onboarding - Tasks", description = "Create, retrieve, update, and delete onboarding tasks")
class OnboardingTaskController(
    val onboardingTaskService: OnboardingTaskService,
) {
//  ========================== Endpoints for users (/me/...) ==========================

    @ResponseStatus(value = HttpStatus.OK)
    @GetMapping("/me/steps/{stepId}/tasks")
    @PreAuthorize("hasRole('USER')")
    fun getOnboardingTasksForMe(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable stepId: UUID,
    ): List<GetOnboardingTasksResponse> {
        return onboardingTaskService.getOnboardingTasksForMe(jwt.subject, stepId)
    }

    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/me/steps/{stepId}/tasks")
    @PreAuthorize("hasRole('USER')")
    fun createOnboardingTaskForMe(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable stepId: UUID,
        @Valid @RequestBody request: CreateOnboardingTaskRequest,
    ): CreateOnboardingTaskResponse {
        return onboardingTaskService.createOnboardingTaskForMe(jwt.subject, stepId, request)
    }

    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/me/tasks/{taskId}")
    @PreAuthorize("hasRole('USER')")
    fun getOnboardingTaskForMe(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable taskId: UUID,
    ): GetOnboardingTaskResponse {
        return onboardingTaskService.getOnboardingTaskForMe(jwt.subject, taskId)
    }

    @ResponseStatus(HttpStatus.OK)
    @PutMapping("/me/tasks/{taskId}")
    @PreAuthorize("hasRole('USER')")
    fun updateOnboardingTaskForMe(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable taskId: UUID,
        @Valid @RequestBody request: UpdateOnboardingTaskRequest,
    ): UpdateOnboardingTaskResponse {
        return onboardingTaskService.updateOnboardingTaskForMe(jwt.subject, taskId, request)
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/me/tasks/{taskId}")
    @PreAuthorize("hasRole('USER')")
    fun deleteOnboardingTaskForMe(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable taskId: UUID,
    ) {
        onboardingTaskService.deleteOnboardingTaskForMe(jwt.subject, taskId)
    }

//  ========================== Endpoints for admins ==========================

    /**
     * Returns all tasks belonging to a specific step.
     *
     * Tasks are leaf nodes with no children, so no nesting applies here. Tasks are
     * ordered by their position within the step.
     *
     * @param stepId The UUID of the onboarding step.
     * @return An ordered list of tasks for the given step.
     */
    @Operation(
        summary = "Get tasks by step ID",
        description = "Returns all onboarding tasks belonging to the specified step, ordered by position. " +
            "Tasks are leaf nodes — no nested content exists.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Ordered list of tasks for the step"),
        ApiResponse(responseCode = "404", description = "No onboarding step found with the given ID"),
    )
    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/steps/{stepId}/tasks")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    fun getOnboardingTasksByStepId(
        @Parameter(description = "UUID of the onboarding step") @PathVariable stepId: UUID,
    ): List<GetOnboardingTaskResponse> {
        return onboardingTaskService.getOnboardingTasksByStepId(stepId)
    }

    /**
     * Creates a new task within the specified step at the given position.
     *
     * Tasks are ordered within a step by a numeric position. If the requested position
     * is already occupied, all tasks at or after that position are shifted forward by one.
     * Tasks are leaf nodes in the onboarding hierarchy and have no children.
     *
     * @param stepId The UUID of the step to add the task to.
     * @param request The task creation request.
     * @return The created task.
     */
    @Operation(
        summary = "Create onboarding task",
        description = "Creates a new task within the specified step at the given position. " +
            "Existing tasks at or after the requested position are shifted forward by one. " +
            "Tasks are leaf nodes and have no children.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Task created successfully"),
        ApiResponse(responseCode = "404", description = "No onboarding step found with the given ID"),
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/steps/{stepId}/tasks")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    fun createOnboardingTask(
        @Parameter(description = "UUID of the onboarding step") @PathVariable stepId: UUID,
        @RequestBody request: CreateOnboardingTaskRequest,
    ): CreateOnboardingTaskResponse {
        return onboardingTaskService.createOnboardingTaskForStepId(stepId, request)
    }

    /**
     * Returns a single onboarding task by its ID.
     *
     * Tasks are leaf nodes with no children, so the response contains only the task's
     * own fields: position, title, description, and finished status.
     *
     * @param taskId The UUID of the onboarding task.
     * @return The onboarding task.
     */
    @Operation(
        summary = "Get onboarding task by ID",
        description = "Returns a single onboarding task by its UUID. Tasks are leaf nodes — no nested content exists.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Onboarding task found"),
        ApiResponse(responseCode = "404", description = "No onboarding task found with the given ID"),
    )
    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/tasks/{taskId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    fun getOnboardingTask(
        @Parameter(description = "UUID of the onboarding task") @PathVariable taskId: UUID,
    ): GetOnboardingTaskResponse {
        return onboardingTaskService.getOnboardingTaskById(taskId)
    }

    /**
     * Updates an existing onboarding task, including its position and finished status.
     *
     * All fields are replaced with the values from the request. If the position changes,
     * sibling tasks within the same step are shifted automatically to maintain contiguous
     * ordering. The finished flag can be toggled freely without any side effects beyond
     * the field update itself.
     *
     * @param taskId The UUID of the task to update.
     * @param request The task update request.
     * @return The updated task.
     */
    @Operation(
        summary = "Update onboarding task",
        description = "Updates all fields of an existing onboarding task, including its position " +
            "and finished status. If the position changes, sibling tasks are shifted automatically " +
            "to maintain contiguous ordering.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Task updated successfully"),
        ApiResponse(responseCode = "404", description = "No onboarding task found with the given ID"),
    )
    @ResponseStatus(HttpStatus.OK)
    @PutMapping("/tasks/{taskId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    fun updateOnboardingTask(
        @Parameter(description = "UUID of the onboarding task to update") @PathVariable taskId: UUID,
        @RequestBody request: UpdateOnboardingTaskRequest,
    ): UpdateOnboardingTaskResponse {
        return onboardingTaskService.updateOnboardingTaskById(taskId, request)
    }

    /**
     * Deletes an onboarding task and reorders remaining siblings.
     *
     * After deletion, all tasks in the same step with a position greater than the
     * deleted task's position are shifted back by one to keep the ordering contiguous.
     *
     * @param taskId The UUID of the task to delete.
     */
    @Operation(
        summary = "Delete onboarding task",
        description = "Permanently deletes the specified onboarding task. " +
            "Subsequent sibling tasks are shifted back by one to keep ordering contiguous.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Task deleted successfully"),
        ApiResponse(responseCode = "404", description = "No onboarding task found with the given ID"),
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/tasks/{taskId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    fun deleteOnboardingTaskForStepId(
        @Parameter(description = "UUID of the onboarding task to delete") @PathVariable taskId: UUID,
    ) {
        onboardingTaskService.deleteOnboardingTaskById(taskId)
    }
}

// Todo: add doc
