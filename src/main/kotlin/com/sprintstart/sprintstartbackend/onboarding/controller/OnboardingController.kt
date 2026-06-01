package com.sprintstart.sprintstartbackend.onboarding.controller

import com.sprintstart.sprintstartbackend.onboarding.model.request.phase.CreateOnboardingPhaseRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.phase.UpdateOnboardingPhaseRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.resource.CreateOnboardingResourceRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.resource.UpdateOnboardingResourceRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.step.CreateOnboardingStepRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.step.UpdateOnboardingStepRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.task.CreateOnboardingTaskRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.task.UpdateOnboardingTaskRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.GetOnboardingPathForUserResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.GetOnboardingPathResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.GetOnboardingPathsResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.phase.CreateOnboardingPhaseResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.phase.GetOnboardingPhaseResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.phase.GetOnboardingPhasesResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.phase.UpdateOnboardingPhaseResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.resource.CreateOnboardingResourceResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.resource.GetOnboardingResourceResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.resource.GetOnboardingResourcesResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.resource.UpdateOnboardingResourceResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.CreateOnboardingStepResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.GetOnboardingStepResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.GetOnboardingStepsResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.UpdateOnboardingStepResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.task.CreateOnboardingTaskResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.task.GetOnboardingTaskResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.task.GetOnboardingTasksResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.task.UpdateOnboardingTaskResponse
import com.sprintstart.sprintstartbackend.onboarding.service.OnboardingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
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
 * REST controller exposing all onboarding management endpoints.
 *
 * Manages the full onboarding hierarchy under the base path `/api/v1/onboarding`.
 * The hierarchy is structured as follows:
 * ```
 * Path (per user)
 *  └── Phase (ordered)
 *       └── Step (ordered, with status)
 *            ├── Task (ordered checklist)
 *            └── Resource (reference links)
 * ```
 * GetAll endpoints return flat lists with no nesting. GetById and GetByParentId endpoints
 * return one level of nesting (e.g. a phase includes its steps). The only exception is
 * [getPathForUser], which returns deep nesting: Path → Phases → Steps.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@Tag(name = "Onboarding", description = "Manage onboarding paths, phases, steps, tasks, and resources")
class OnboardingController(
    val onboardingService: OnboardingService,
) {
    // -------------------------------------------------------------------------
    // Paths
    // -------------------------------------------------------------------------

    /**
     * Returns all onboarding paths across all users.
     *
     * This is a flat listing intended for administrative overviews. No phases, steps,
     * tasks, or resources are included in the response.
     *
     * @return A flat list of all onboarding paths.
     */
    @Operation(
        summary = "Get all onboarding paths",
        description = "Returns a flat list of all onboarding paths across all users. No nested content is included.",
    )
    @ApiResponse(responseCode = "200", description = "Flat list of all onboarding paths")
    @GetMapping("/paths")
    fun getAllPaths(): List<GetOnboardingPathsResponse> {
        return onboardingService.getAllOnboardingPaths()
    }

    /**
     * Returns a single onboarding path by its ID, including its direct phases.
     *
     * The response includes one level of nesting: the path and its phases are returned,
     * but the phases do not include their steps.
     *
     * @param pathId The UUID of the onboarding path.
     * @return The onboarding path with its phases.
     */
    @Operation(
        summary = "Get onboarding path by ID",
        description = "Returns a single onboarding path by its UUID. Includes one level of nesting: the path's direct phases are included, but steps within those phases are not.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Onboarding path with direct phases"),
        ApiResponse(responseCode = "404", description = "No onboarding path found with the given ID"),
    )
    @GetMapping("/paths/{pathId}")
    fun getPath(
        @Parameter(description = "UUID of the onboarding path") @PathVariable pathId: UUID,
    ): GetOnboardingPathResponse {
        return onboardingService.getOnboardingPath(pathId)
    }

    /**
     * Returns the onboarding path for a specific user with deep nesting.
     *
     * This is the primary endpoint for rendering a user's full onboarding view. Unlike
     * other get-by-ID endpoints that return only one level of nesting, this endpoint
     * returns the full Path → Phases → Steps structure. Tasks and resources within steps
     * are not included. Both the user and their path must exist, otherwise a 404 is returned.
     *
     * @param userId The UUID of the user.
     * @return The user's onboarding path with nested phases and steps.
     */
    @Operation(
        summary = "Get onboarding path for a user",
        description = "Returns the onboarding path for the specified user with deep nesting: Path → Phases → Steps. " +
            "This is the primary endpoint for rendering a user's onboarding view. " +
            "Tasks and resources within steps are not included. " +
            "Returns 404 if the user does not exist or has no onboarding path assigned.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Onboarding path with nested phases and steps"),
        ApiResponse(responseCode = "404", description = "No user or onboarding path found for the given user ID"),
    )
    @GetMapping("/{userId}/path")
    fun getPathForUser(
        @Parameter(description = "UUID of the user") @PathVariable userId: UUID,
    ): GetOnboardingPathForUserResponse {
        return onboardingService.getOnboardingPathByUserId(userId)
    }

    /**
     * Deletes an onboarding path by its ID.
     *
     * This is a hard delete. All associated phases, steps, tasks, and resources are
     * removed via cascade. This operation is not reversible.
     *
     * @param pathId The UUID of the onboarding path to delete.
     */
    @Operation(
        summary = "Delete onboarding path by ID",
        description = "Permanently deletes the onboarding path with the given UUID. " +
            "All associated phases, steps, tasks, and resources are removed via cascade. This operation is not reversible.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Onboarding path deleted"),
        ApiResponse(responseCode = "404", description = "No onboarding path found with the given ID"),
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/paths/{pathId}")
    fun deletePath(
        @Parameter(description = "UUID of the onboarding path to delete") @PathVariable pathId: UUID,
    ) {
        onboardingService.deleteOnboardingPathById(pathId)
    }

    /**
     * Deletes the onboarding path associated with a specific user.
     *
     * Behaves identically to deleting by path ID, but accepts a user ID instead.
     * Useful when the path ID is unknown but the user ID is available. The user must
     * exist in the system, otherwise a 404 is returned before any deletion is attempted.
     *
     * @param userId The UUID of the user whose onboarding path should be deleted.
     */
    @Operation(
        summary = "Delete onboarding path by user ID",
        description = "Permanently deletes the onboarding path belonging to the specified user. " +
            "The user must exist in the system — a 404 is returned if the user is not found. " +
            "All associated phases, steps, tasks, and resources are removed via cascade.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Onboarding path deleted"),
        ApiResponse(responseCode = "404", description = "No user or onboarding path found for the given user ID"),
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{userId}/path")
    fun deletePathByUserId(
        @Parameter(description = "UUID of the user whose onboarding path should be deleted") @PathVariable userId: UUID,
    ) {
        onboardingService.deleteOnboardingPathByUserId(userId)
    }

    // -------------------------------------------------------------------------
    // Phases
    // -------------------------------------------------------------------------

    /**
     * Creates a new phase within the specified path at the given position.
     *
     * Phases are ordered within a path by a numeric position. If the requested position
     * is already occupied, all phases at or after that position are shifted one place
     * forward to make room. This means position values are not stable identifiers and
     * should not be stored externally.
     *
     * @param pathId The UUID of the path to add the phase to.
     * @param request The phase creation request containing position, title, and description.
     * @return The created phase.
     */
    @Operation(
        summary = "Create onboarding phase",
        description = "Creates a new phase within the specified path at the given position. " +
            "If the position is already occupied, all phases at or after that position are shifted forward by one. " +
            "Position values are not stable and should not be used as external references.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Phase created successfully"),
        ApiResponse(responseCode = "404", description = "No onboarding path found with the given ID"),
    )
    @PostMapping("/paths/{pathId}/phases")
    fun createOnboardingPhase(
        @Parameter(description = "UUID of the onboarding path") @PathVariable pathId: UUID,
        @RequestBody request: CreateOnboardingPhaseRequest,
    ): CreateOnboardingPhaseResponse {
        return onboardingService.createOnboardingPhaseForPathId(pathId, request)
    }

    /**
     * Returns all onboarding phases across all paths.
     *
     * This is a flat listing with no nested content. Steps within each phase are not
     * included. Intended for administrative use or bulk exports.
     *
     * @return A flat list of all onboarding phases.
     */
    @Operation(
        summary = "Get all onboarding phases",
        description = "Returns a flat list of all onboarding phases across all paths. No nested content is included. Intended for administrative overviews.",
    )
    @ApiResponse(responseCode = "200", description = "Flat list of all onboarding phases")
    @GetMapping("/phases")
    fun getOnboardingPhases(): List<GetOnboardingPhasesResponse> {
        return onboardingService.getOnboardingPhases()
    }

    /**
     * Returns all phases belonging to a specific path, including their direct steps.
     *
     * Returns one level of nesting: each phase in the response includes its direct steps,
     * but tasks and resources within those steps are not included. Phases are ordered
     * by their position within the path.
     *
     * @param pathId The UUID of the onboarding path.
     * @return An ordered list of phases with their direct steps.
     */
    @Operation(
        summary = "Get phases by path ID",
        description = "Returns all onboarding phases belonging to the specified path, ordered by position. " +
            "Each phase includes its direct steps (one level of nesting). " +
            "Tasks and resources within those steps are not included.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Ordered list of phases with direct steps"),
        ApiResponse(responseCode = "404", description = "No onboarding path found with the given ID"),
    )
    @GetMapping("/paths/{pathId}/phases")
    fun getAllOnboardingPhasesByPathId(
        @Parameter(description = "UUID of the onboarding path") @PathVariable pathId: UUID,
    ): List<GetOnboardingPhaseResponse> {
        return onboardingService.getOnboardingPhasesByPathId(pathId)
    }

    /**
     * Returns a single onboarding phase by its ID, including its direct steps.
     *
     * Returns one level of nesting: the phase and its direct steps are included,
     * but tasks and resources within those steps are not.
     *
     * @param phaseId The UUID of the onboarding phase.
     * @return The onboarding phase with its direct steps.
     */
    @Operation(
        summary = "Get onboarding phase by ID",
        description = "Returns a single onboarding phase by its UUID. " +
            "Includes one level of nesting: the phase's direct steps are included, but tasks and resources within those steps are not.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Onboarding phase with direct steps"),
        ApiResponse(responseCode = "404", description = "No onboarding phase found with the given ID"),
    )
    @GetMapping("/phases/{phaseId}")
    fun getOnboardingPhase(
        @Parameter(description = "UUID of the onboarding phase") @PathVariable phaseId: UUID,
    ): GetOnboardingPhaseResponse {
        return onboardingService.getOnboardingPhase(phaseId)
    }

    /**
     * Updates an existing onboarding phase, including its position within the path.
     *
     * All fields are replaced with the values from the request. If the position changes,
     * sibling phases between the old and new positions are automatically shifted to maintain
     * a contiguous, gap-free ordering. Moving a phase forward (higher position) shifts
     * intermediary phases back by one; moving it backward shifts them forward by one.
     *
     * @param phaseId The UUID of the phase to update.
     * @param request The phase update request containing the new position, title, and description.
     * @return The updated phase.
     */
    @Operation(
        summary = "Update onboarding phase",
        description = "Updates all fields of an existing onboarding phase, including its position. " +
            "If the position changes, sibling phases between the old and new positions are shifted automatically " +
            "to maintain contiguous ordering. Moving forward shifts intermediaries back; moving backward shifts them forward.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Phase updated successfully"),
        ApiResponse(responseCode = "404", description = "No onboarding phase found with the given ID"),
    )
    @PutMapping("/phases/{phaseId}")
    fun updateOnboardingPhase(
        @Parameter(description = "UUID of the onboarding phase to update") @PathVariable phaseId: UUID,
        @RequestBody request: UpdateOnboardingPhaseRequest,
    ): UpdateOnboardingPhaseResponse {
        return onboardingService.updateOnboardingPhase(phaseId, request)
    }

    /**
     * Deletes an onboarding phase and reorders remaining siblings.
     *
     * After deletion, all phases in the same path with a position greater than the
     * deleted phase's position are shifted back by one, keeping the ordering contiguous.
     * All child steps, tasks, and resources are removed via cascade.
     *
     * @param phaseId The UUID of the phase to delete.
     */
    @Operation(
        summary = "Delete onboarding phase",
        description = "Permanently deletes the specified onboarding phase. " +
            "Subsequent sibling phases are shifted back by one to keep ordering contiguous. " +
            "All child steps, tasks, and resources are removed via cascade.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Phase deleted successfully"),
        ApiResponse(responseCode = "404", description = "No onboarding phase found with the given ID"),
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/phases/{phaseId}")
    fun deleteOnboardingPhaseForPathId(
        @Parameter(description = "UUID of the onboarding phase to delete") @PathVariable phaseId: UUID,
    ) {
        onboardingService.deleteOnboardingPhase(phaseId)
    }

    // -------------------------------------------------------------------------
    // Steps
    // -------------------------------------------------------------------------

    /**
     * Creates a new step within the specified phase at the given position.
     *
     * Steps are ordered within a phase by a numeric position. If the requested position
     * is already occupied, all steps at or after that position are shifted forward by one.
     * The new step is always initialized with status [StepStatus.WAITING] regardless of
     * what is passed in the request.
     *
     * @param phaseId The UUID of the phase to add the step to.
     * @param request The step creation request.
     * @return The created step.
     */
    @Operation(
        summary = "Create onboarding step",
        description = "Creates a new step within the specified phase at the given position. " +
            "Existing steps at or after the requested position are shifted forward by one. " +
            "The new step is always initialized with status WAITING regardless of request content.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Step created successfully"),
        ApiResponse(responseCode = "404", description = "No onboarding phase found with the given ID"),
    )
    @PostMapping("/phases/{phaseId}/steps")
    fun createOnboardingStep(
        @Parameter(description = "UUID of the onboarding phase") @PathVariable phaseId: UUID,
        @RequestBody request: CreateOnboardingStepRequest,
    ): CreateOnboardingStepResponse {
        return onboardingService.createOnboardingStepForPhaseId(phaseId, request)
    }

    /**
     * Returns all onboarding steps across all phases.
     *
     * This is a flat listing with no nested content. Tasks and resources within each
     * step are not included. Intended for administrative use or bulk exports.
     *
     * @return A flat list of all onboarding steps.
     */
    @Operation(
        summary = "Get all onboarding steps",
        description = "Returns a flat list of all onboarding steps across all phases. No nested content is included. Intended for administrative overviews.",
    )
    @ApiResponse(responseCode = "200", description = "Flat list of all onboarding steps")
    @GetMapping("/steps")
    fun getOnboardingSteps(): List<GetOnboardingStepsResponse> {
        return onboardingService.getOnboardingSteps()
    }

    /**
     * Returns all steps belonging to a specific phase, including their direct tasks and resources.
     *
     * Returns one level of nesting: each step includes its direct tasks and resources.
     * Steps are ordered by their position within the phase.
     *
     * @param phaseId The UUID of the onboarding phase.
     * @return An ordered list of steps with their direct tasks and resources.
     */
    @Operation(
        summary = "Get steps by phase ID",
        description = "Returns all onboarding steps belonging to the specified phase, ordered by position. " +
            "Each step includes its direct tasks and resources (one level of nesting).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Ordered list of steps with direct tasks and resources"),
        ApiResponse(responseCode = "404", description = "No onboarding phase found with the given ID"),
    )
    @GetMapping("/phases/{phaseId}/steps")
    fun getOnboardingStepsForPhaseId(
        @Parameter(description = "UUID of the onboarding phase") @PathVariable phaseId: UUID,
    ): List<GetOnboardingStepResponse> {
        return onboardingService.getOnboardingStepsByPhaseId(phaseId)
    }

    /**
     * Returns a single onboarding step by its ID, including its direct tasks and resources.
     *
     * Returns one level of nesting: the step's direct tasks and resources are included.
     *
     * @param stepId The UUID of the onboarding step.
     * @return The onboarding step with its tasks and resources.
     */
    @Operation(
        summary = "Get onboarding step by ID",
        description = "Returns a single onboarding step by its UUID. " +
            "Includes one level of nesting: the step's direct tasks and resources are included.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Onboarding step with direct tasks and resources"),
        ApiResponse(responseCode = "404", description = "No onboarding step found with the given ID"),
    )
    @GetMapping("/steps/{stepId}")
    fun getOnboardingStep(
        @Parameter(description = "UUID of the onboarding step") @PathVariable stepId: UUID,
    ): GetOnboardingStepResponse {
        return onboardingService.getOnboardingStep(stepId)
    }

    /**
     * Updates an existing onboarding step, including its status and position.
     *
     * All fields are replaced with the values from the request. If the position changes,
     * sibling steps are shifted automatically to maintain contiguous ordering. Status
     * transitions carry side-effects: transitioning to FINISHED records a completion
     * timestamp; transitioning to SKIPPED records a completion timestamp and stores the
     * skip reason (defaults to "No reason given" if omitted); transitioning back to
     * WAITING clears both the completion timestamp and the skip reason.
     *
     * @param stepId The UUID of the step to update.
     * @param request The step update request.
     * @return The updated step.
     */
    @Operation(
        summary = "Update onboarding step",
        description = "Updates all fields of an existing onboarding step, including its status and position. " +
            "If the position changes, sibling steps are shifted automatically. " +
            "Status transitions carry side-effects: " +
            "FINISHED records a completion timestamp; " +
            "SKIPPED records a completion timestamp and a skip reason (defaults to 'No reason given' if omitted); " +
            "WAITING clears both the completion timestamp and skip reason.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Step updated successfully"),
        ApiResponse(responseCode = "404", description = "No onboarding step found with the given ID"),
    )
    @PutMapping("/steps/{stepId}")
    fun updateOnboardingStep(
        @Parameter(description = "UUID of the onboarding step to update") @PathVariable stepId: UUID,
        @RequestBody request: UpdateOnboardingStepRequest,
    ): UpdateOnboardingStepResponse {
        return onboardingService.updateOnboardingStep(stepId, request)
    }

    /**
     * Deletes an onboarding step and reorders remaining siblings.
     *
     * After deletion, all steps in the same phase with a position greater than the
     * deleted step's position are shifted back by one. All child tasks and resources
     * are removed via cascade.
     *
     * @param stepId The UUID of the step to delete.
     */
    @Operation(
        summary = "Delete onboarding step",
        description = "Permanently deletes the specified onboarding step. " +
            "Subsequent sibling steps are shifted back by one to keep ordering contiguous. " +
            "All child tasks and resources are removed via cascade.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Step deleted successfully"),
        ApiResponse(responseCode = "404", description = "No onboarding step found with the given ID"),
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/steps/{stepId}")
    fun deleteOnboardingStepForPhaseId(
        @Parameter(description = "UUID of the onboarding step to delete") @PathVariable stepId: UUID,
    ) {
        onboardingService.deleteOnboardingStep(stepId)
    }

    // -------------------------------------------------------------------------
    // Tasks
    // -------------------------------------------------------------------------

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
        ApiResponse(responseCode = "200", description = "Task created successfully"),
        ApiResponse(responseCode = "404", description = "No onboarding step found with the given ID"),
    )
    @PostMapping("/steps/{stepId}/tasks")
    fun createOnboardingTask(
        @Parameter(description = "UUID of the onboarding step") @PathVariable stepId: UUID,
        @RequestBody request: CreateOnboardingTaskRequest,
    ): CreateOnboardingTaskResponse {
        return onboardingService.createOnboardingTaskForStepId(stepId, request)
    }

    /**
     * Returns all onboarding tasks across all steps.
     *
     * This is a flat listing with no nested content. Tasks are leaf nodes, so there
     * is nothing to nest regardless. Intended for administrative use or bulk exports.
     *
     * @return A flat list of all onboarding tasks.
     */
    @Operation(
        summary = "Get all onboarding tasks",
        description = "Returns a flat list of all onboarding tasks across all steps. Tasks are leaf nodes — no nested content exists.",
    )
    @ApiResponse(responseCode = "200", description = "Flat list of all onboarding tasks")
    @GetMapping("/tasks")
    fun getOnboardingTasks(): List<GetOnboardingTasksResponse> {
        return onboardingService.getOnboardingTasks()
    }

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
    @GetMapping("/steps/{stepId}/tasks")
    fun getOnboardingTasksByStepId(
        @Parameter(description = "UUID of the onboarding step") @PathVariable stepId: UUID,
    ): List<GetOnboardingTaskResponse> {
        return onboardingService.getOnboardingTasksByStepId(stepId)
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
    @GetMapping("/tasks/{taskId}")
    fun getOnboardingTask(
        @Parameter(description = "UUID of the onboarding task") @PathVariable taskId: UUID,
    ): GetOnboardingTaskResponse {
        return onboardingService.getOnboardingTask(taskId)
    }

    /**
     * Updates an existing onboarding task, including its position and finished status.
     *
     * All fields are replaced with the values from the request. If the position changes,
     * sibling tasks within the same step are shifted automatically to maintain contiguous
     * ordering. The finished flag can be toggled freely without any side-effects beyond
     * the field update itself.
     *
     * @param taskId The UUID of the task to update.
     * @param request The task update request.
     * @return The updated task.
     */
    @Operation(
        summary = "Update onboarding task",
        description = "Updates all fields of an existing onboarding task, including its position and finished status. " +
            "If the position changes, sibling tasks are shifted automatically to maintain contiguous ordering.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Task updated successfully"),
        ApiResponse(responseCode = "404", description = "No onboarding task found with the given ID"),
    )
    @PutMapping("/tasks/{taskId}")
    fun updateOnboardingTask(
        @Parameter(description = "UUID of the onboarding task to update") @PathVariable taskId: UUID,
        @RequestBody request: UpdateOnboardingTaskRequest,
    ): UpdateOnboardingTaskResponse {
        return onboardingService.updateOnboardingTask(taskId, request)
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
        ApiResponse(responseCode = "200", description = "Task deleted successfully"),
        ApiResponse(responseCode = "404", description = "No onboarding task found with the given ID"),
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/tasks/{taskId}")
    fun deleteOnboardingTaskForStepId(
        @Parameter(description = "UUID of the onboarding task to delete") @PathVariable taskId: UUID,
    ) {
        onboardingService.deleteOnboardingTask(taskId)
    }

    // -------------------------------------------------------------------------
    // Resources
    // -------------------------------------------------------------------------

    /**
     * Creates a new resource (reference link) attached to the specified step.
     *
     * Resources are unordered reference materials (e.g. documentation links, videos)
     * associated with a step. Unlike phases, steps, and tasks, resources have no
     * position field and are not subject to reordering.
     *
     * @param stepId The UUID of the step to attach the resource to.
     * @param request The resource creation request containing title, description, and URL.
     * @return The created resource.
     */
    @Operation(
        summary = "Create onboarding resource",
        description = "Creates a new reference resource (e.g. a documentation link or video) attached to the specified step. " +
            "Resources are unordered — unlike phases, steps, and tasks, they have no position field.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Resource created successfully"),
        ApiResponse(responseCode = "404", description = "No onboarding step found with the given ID"),
    )
    @PostMapping("/steps/{stepId}/resources")
    fun createOnboardingResourceForStepId(
        @Parameter(description = "UUID of the onboarding step") @PathVariable stepId: UUID,
        @RequestBody request: CreateOnboardingResourceRequest,
    ): CreateOnboardingResourceResponse {
        return onboardingService.createOnboardingResourceForStepId(stepId, request)
    }

    /**
     * Returns all onboarding resources across all steps.
     *
     * This is a flat listing intended for administrative use. Resources are leaf nodes
     * with no nested content.
     *
     * @return A flat list of all onboarding resources.
     */
    @Operation(
        summary = "Get all onboarding resources",
        description = "Returns a flat list of all onboarding resources across all steps. Resources are leaf nodes — no nested content exists.",
    )
    @ApiResponse(responseCode = "200", description = "Flat list of all onboarding resources")
    @GetMapping("/resources")
    fun getOnboardingResources(): List<GetOnboardingResourcesResponse> {
        return onboardingService.getOnboardingResources()
    }

    /**
     * Returns all resources attached to a specific step.
     *
     * Resources are leaf nodes with no children and are unordered, so the response is
     * a flat list. The order of results is not guaranteed.
     *
     * @param stepId The UUID of the onboarding step.
     * @return A list of resources attached to the given step.
     */
    @Operation(
        summary = "Get resources by step ID",
        description = "Returns all onboarding resources attached to the specified step. " +
            "Resources are unordered leaf nodes — no nested content exists and result order is not guaranteed.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "List of resources for the step"),
        ApiResponse(responseCode = "404", description = "No onboarding step found with the given ID"),
    )
    @GetMapping("/steps/{stepId}/resources")
    fun getOnboardingResourcesByStepId(
        @Parameter(description = "UUID of the onboarding step") @PathVariable stepId: UUID,
    ): List<GetOnboardingResourceResponse> {
        return onboardingService.getOnboardingResourceByStepId(stepId)
    }

    /**
     * Returns a single onboarding resource by its ID.
     *
     * Resources are leaf nodes with no children. The response contains only the
     * resource's own fields: title, description, and URL.
     *
     * @param resourceId The UUID of the onboarding resource.
     * @return The onboarding resource.
     */
    @Operation(
        summary = "Get onboarding resource by ID",
        description = "Returns a single onboarding resource by its UUID. Resources are leaf nodes — no nested content exists.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Onboarding resource found"),
        ApiResponse(responseCode = "404", description = "No onboarding resource found with the given ID"),
    )
    @GetMapping("/resources/{resourceId}")
    fun getOnboardingResource(
        @Parameter(description = "UUID of the onboarding resource") @PathVariable resourceId: UUID,
    ): GetOnboardingResourceResponse {
        return onboardingService.getOnboardingResource(resourceId)
    }

    /**
     * Updates an existing onboarding resource's title, description, and URL.
     *
     * All three fields are replaced with the values from the request. Resources have
     * no position and no status, so there are no ordering side-effects to consider.
     *
     * @param resourceId The UUID of the resource to update.
     * @param request The resource update request.
     * @return The updated resource.
     */
    @Operation(
        summary = "Update onboarding resource",
        description = "Replaces the title, description, and URL of an existing onboarding resource. " +
            "Resources have no position or status, so there are no ordering side-effects.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Resource updated successfully"),
        ApiResponse(responseCode = "404", description = "No onboarding resource found with the given ID"),
    )
    @PutMapping("/resources/{resourceId}")
    fun updateOnboardingResource(
        @Parameter(description = "UUID of the onboarding resource to update") @PathVariable resourceId: UUID,
        @RequestBody request: UpdateOnboardingResourceRequest,
    ): UpdateOnboardingResourceResponse {
        return onboardingService.updateOnboardingResource(resourceId, request)
    }

    /**
     * Deletes an onboarding resource by its ID.
     *
     * Resources are unordered, so no sibling reordering is needed after deletion.
     * This operation is not reversible.
     *
     * @param resourceId The UUID of the resource to delete.
     */
    @Operation(
        summary = "Delete onboarding resource",
        description = "Permanently deletes the specified onboarding resource. " +
            "Resources are unordered, so no sibling reordering is needed after deletion.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Resource deleted successfully"),
        ApiResponse(responseCode = "404", description = "No onboarding resource found with the given ID"),
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/resources/{resourceId}")
    fun deleteOnboardingResourceForStepId(
        @Parameter(description = "UUID of the onboarding resource to delete") @PathVariable resourceId: UUID,
    ) {
        onboardingService.deleteOnboardingResource(resourceId)
    }
}