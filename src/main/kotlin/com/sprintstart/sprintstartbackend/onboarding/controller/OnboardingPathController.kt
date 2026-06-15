package com.sprintstart.sprintstartbackend.onboarding.controller

import com.sprintstart.sprintstartbackend.onboarding.model.response.path.GetOnboardingPathForUserResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.GetOnboardingPathResponse
import com.sprintstart.sprintstartbackend.onboarding.service.OnboardingPathService
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST controller for managing onboarding paths.
 *
 * Exposes endpoints under `/api/v1/onboarding` for retrieving and deleting onboarding paths.
 *
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@Tag(name = "Onboarding - Paths", description = "Retrieve and delete onboarding paths")
class OnboardingPathController(
    private val onboardingPathService: OnboardingPathService,
) {
//  ========================== Endpoints for users (/me/...) ==========================

    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/me/path")
    @PreAuthorize("hasRole('USER')")
    fun getPathForMe(
        @AuthenticationPrincipal jwt: Jwt,
    ): GetOnboardingPathForUserResponse {
        return onboardingPathService.getOnboardingPathForMe(jwt.subject)
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/me/path")
    @PreAuthorize("hasRole('USER')")
    fun deletePathByUserId(
        @AuthenticationPrincipal jwt: Jwt,
    ) {
        onboardingPathService.deleteOnboardingPathForMe(jwt.subject)
    }

//  ========================== Endpoints for admins ==========================

    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/users/{userId}/path")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    fun getOnboardingPathForUserId(
        @Parameter(description = "UUID of the onboarding path") @PathVariable userId: UUID,
    ): GetOnboardingPathResponse {
        return onboardingPathService.getOnboardingPathByUserId(userId)
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/users/{userId}/path")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    fun deletePathByUserId(
        @Parameter(description = "UUID of the user whose onboarding path should be deleted") @PathVariable userId: UUID,
    ) {
        onboardingPathService.deleteOnboardingPathByUserId(userId)
    }
}

// TODO: add doc
