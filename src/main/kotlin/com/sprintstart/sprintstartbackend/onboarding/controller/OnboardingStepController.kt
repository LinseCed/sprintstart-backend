package com.sprintstart.sprintstartbackend.onboarding.controller

import com.sprintstart.sprintstartbackend.onboarding.model.request.step.CreateOnboardingStepRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.step.UpdateOnboardingStepRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.CreateOnboardingStepResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.GetOnboardingStepResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.GetOnboardingStepsResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.UpdateOnboardingStepResponse
import com.sprintstart.sprintstartbackend.onboarding.service.OnboardingStepService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
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
 * REST controller for managing onboarding steps.
 *
 * Exposes endpoints under `/api/v1/onboarding` for creating, retrieving, updating,
 * and deleting onboarding steps. Steps are the third level of the onboarding hierarchy,
 * sitting below a phase and above tasks and resources.
 *
 * Steps are ordered within their parent phase by a numeric position. Insertions, updates,
 * and deletions automatically shift sibling steps to maintain a contiguous, gap-free ordering.
 *
 * Steps carry a status field with the following transition side-effects:
 * - `FINISHED` — records a completion timestamp
 * - `SKIPPED` — records a completion timestamp and a skip reason
 * - `WAITING` — clears both the completion timestamp and the skip reason
 *
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@Tag(name = "Onboarding - Steps", description = "Create, retrieve, update, and delete onboarding steps")
class OnboardingStepController(
    val onboardingStepService: OnboardingStepService,
) {
//  ========================== Endpoints for users (/me/...) ==========================

    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/me/phases/{phaseId}/steps")
    @PreAuthorize("hasRole('ROLE_USER')")
    fun getOnboardingStepsForMe(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable phaseId: UUID,
    ): List<GetOnboardingStepsResponse> {
        return onboardingStepService.getOnboardingStepsForMe(jwt.subject, phaseId)
    }

    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/me/phases/{phaseId}/steps")
    @PreAuthorize("hasRole('ROLE_USER')")
    fun createOnboardingStepForMe(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable phaseId: UUID,
        @RequestBody request: CreateOnboardingStepRequest,
    ): CreateOnboardingStepResponse {
        return onboardingStepService.createOnboardingStepForMe(jwt.subject, phaseId, request)
    }

    @ResponseStatus(HttpStatus.OK)
    @GetMapping("me/step/{stepId}")
    @PreAuthorize("hasRole('ROLE_USER')")
    fun getOnboardingStepForMe(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable stepId: UUID,
    ): GetOnboardingStepResponse {
        return onboardingStepService.getOnboardingStepForMe(jwt.subject, stepId)
    }

    @ResponseStatus(HttpStatus.OK)
    @PutMapping("/me/step/{stepId}")
    @PreAuthorize("hasRole('ROLE_USER')")
    fun updateOnboardingStepForMe(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable stepId: UUID,
        @RequestBody request: UpdateOnboardingStepRequest,
    ): UpdateOnboardingStepResponse {
        return onboardingStepService.updateOnboardingStepForMe(jwt.subject, stepId, request)
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/me/step/{stepId}")
    @PreAuthorize("hasRole('ROLE_USER')")
    fun deleteOnboardingStepForMe(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable stepId: UUID,
    ) {
        onboardingStepService.deleteOnboardingStepForMe(jwt.subject, stepId)
    }

//  ========================== Endpoints for admins (/users/{userId}/path/phases/...) ==========================

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
    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/phases/{phaseId}/steps")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_PM', 'ROLE_HR')")
    fun getOnboardingStepsForPhaseId(
        @Parameter(description = "UUID of the onboarding phase") @PathVariable phaseId: UUID,
    ): List<GetOnboardingStepResponse> {
        return onboardingStepService.getOnboardingStepsByPhaseId(phaseId)
    }

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
        ApiResponse(responseCode = "201", description = "Step created successfully"),
        ApiResponse(responseCode = "404", description = "No onboarding phase found with the given ID"),
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/phases/{phaseId}/steps")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_PM', 'ROLE_HR')")
    fun createOnboardingStep(
        @Parameter(description = "UUID of the onboarding phase") @PathVariable phaseId: UUID,
        @RequestBody request: CreateOnboardingStepRequest,
    ): CreateOnboardingStepResponse {
        return onboardingStepService.createOnboardingStepForPhaseId(phaseId, request)
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
    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/steps/{stepId}")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_PM', 'ROLE_HR')")
    fun getOnboardingStep(
        @Parameter(description = "UUID of the onboarding step") @PathVariable stepId: UUID,
    ): GetOnboardingStepResponse {
        return onboardingStepService.getOnboardingStepById(stepId)
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
    @ResponseStatus(HttpStatus.OK)
    @PutMapping("/steps/{stepId}")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_PM', 'ROLE_HR')")
    fun updateOnboardingStep(
        @Parameter(description = "UUID of the onboarding step to update") @PathVariable stepId: UUID,
        @RequestBody request: UpdateOnboardingStepRequest,
    ): UpdateOnboardingStepResponse {
        return onboardingStepService.updateOnboardingStepById(stepId, request)
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
        ApiResponse(responseCode = "204", description = "Step deleted successfully"),
        ApiResponse(responseCode = "404", description = "No onboarding step found with the given ID"),
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/steps/{stepId}")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_PM', 'ROLE_HR')")
    fun deleteOnboardingStepById(
        @Parameter(description = "UUID of the onboarding step to delete") @PathVariable stepId: UUID,
    ) {
        onboardingStepService.deleteOnboardingStepById(stepId)
    }
}
