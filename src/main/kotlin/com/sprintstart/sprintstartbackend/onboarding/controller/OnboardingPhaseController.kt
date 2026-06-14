package com.sprintstart.sprintstartbackend.onboarding.controller

import com.sprintstart.sprintstartbackend.onboarding.model.request.phase.CreateOnboardingPhaseRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.phase.UpdateOnboardingPhaseRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.phase.CreateOnboardingPhaseResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.phase.GetOnboardingPhaseResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.phase.GetOnboardingPhasesResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.phase.UpdateOnboardingPhaseResponse
import com.sprintstart.sprintstartbackend.onboarding.service.OnboardingPhaseService
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
 * Update doc
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@Tag(name = "Onboarding - Phases", description = "Create, retrieve, update, and delete onboarding phases")
class OnboardingPhaseController(
    val onboardingPhaseService: OnboardingPhaseService,
) {
//  ========================== Endpoints for users (/me/path/...) ==========================

    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/me/path/phases")
    @PreAuthorize("hasRole('ROLE_USER')")
    fun getOnboardingPhasesForMe(
        @AuthenticationPrincipal jwt: Jwt,
    ): List<GetOnboardingPhasesResponse> {
        return onboardingPhaseService.getOnboardingPhasesForMe(jwt.subject)
    }

    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/me/path/phases")
    @PreAuthorize("hasRole('ROLE_USER')")
    fun createOnboardingPhaseForUser(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: CreateOnboardingPhaseRequest,
    ): CreateOnboardingPhaseResponse {
        return onboardingPhaseService.createOnboardingPhaseForMe(jwt.subject, request)
    }

    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/me/path/phases/{phaseId}")
    @PreAuthorize("hasRole('ROLE_USER')")
    fun getOnboardingPhaseForMe(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable phaseId: UUID,
    ): GetOnboardingPhaseResponse {
        return onboardingPhaseService.getOnboardingPhaseForMe(jwt.subject, phaseId)
    }

    @ResponseStatus(HttpStatus.OK)
    @PutMapping("/me/path/phases/{phaseId}")
    @PreAuthorize("hasRole('ROLE_USER')")
    fun updateOnboardingPhaseForUser(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable phaseId: UUID,
        @Valid @RequestBody request: UpdateOnboardingPhaseRequest,
    ): UpdateOnboardingPhaseResponse {
        return onboardingPhaseService.updateOnboardingPhaseForMe(jwt.subject, phaseId, request)
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/me/path/phases/{phaseId}")
    @PreAuthorize("hasRole('ROLE_USER')")
    fun deleteOnboardingPhaseForMe(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable phaseId: UUID,
    ) {
        onboardingPhaseService.deleteOnboardingPhaseForMe(jwt.subject, phaseId)
    }

//  ========================== Endpoints for admins (/users/{userId}/path/phases/...) ==========================

    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/users/{userId}/path/phases")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_PM', 'ROLE_HR')")
    fun getAllOnboardingPhasesForUser(
        @PathVariable userId: UUID,
    ): List<GetOnboardingPhasesResponse> {
        return onboardingPhaseService.getOnboardingPhasesForUser(userId)
    }

    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/users/{userId}/path/phases")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_PM', 'ROLE_HR')")
    fun createOnboardingPhaseForUser(
        @PathVariable userId: UUID,
        @RequestBody request: CreateOnboardingPhaseRequest,
    ): CreateOnboardingPhaseResponse {
        return onboardingPhaseService.createOnboardingPhaseForUserId(userId, request)
    }

    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/phases/{phaseId}")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_PM', 'ROLE_HR')")
    fun getOnboardingPhaseForUser(
        @Parameter(description = "UUID of the onboarding phase") @PathVariable phaseId: UUID,
    ): GetOnboardingPhaseResponse {
        return onboardingPhaseService.getOnboardingPhaseById(phaseId)
    }

    @ResponseStatus(HttpStatus.OK)
    @PutMapping("/phases/{phaseId}")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_PM', 'ROLE_HR')")
    fun updateOnboardingPhaseForUser(
        @Parameter(description = "UUID of the onboarding phase to update") @PathVariable phaseId: UUID,
        @RequestBody request: UpdateOnboardingPhaseRequest,
    ): UpdateOnboardingPhaseResponse {
        return onboardingPhaseService.updateOnboardingPhaseById(phaseId, request)
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
        ApiResponse(responseCode = "204", description = "Phase deleted successfully"),
        ApiResponse(responseCode = "404", description = "No onboarding phase found with the given ID"),
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/phases/{phaseId}")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_PM', 'ROLE_HR')")
    fun deleteOnboardingPhaseForUser(
        @Parameter(description = "UUID of the onboarding phase to delete") @PathVariable phaseId: UUID,
    ) {
        onboardingPhaseService.deleteOnboardingPhaseById(phaseId)
    }
}

// TODO: add doc
