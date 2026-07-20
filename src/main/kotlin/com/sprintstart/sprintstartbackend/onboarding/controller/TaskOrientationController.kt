package com.sprintstart.sprintstartbackend.onboarding.controller

import com.sprintstart.sprintstartbackend.onboarding.model.response.orientation.MyOrientationResponse
import com.sprintstart.sprintstartbackend.onboarding.service.TaskOrientationService
import com.sprintstart.sprintstartbackend.user.external.UserApi
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Task-scoped orientation: what this project already says about doing the task a hire has.
 *
 * Self-serve only. There is no PM surface here and no approval lifecycle, unlike competency modules:
 * a packet is assembled on demand, cached against the corpus it was built from, and thrown away when
 * that corpus moves. Nobody stands between a hire and their orientation.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@Tag(
    name = "Onboarding - Orientation",
    description = "What the project already says about doing the task you have",
)
class TaskOrientationController(
    private val taskOrientationService: TaskOrientationService,
    private val userApi: UserApi,
) {
    @Operation(
        summary = "Orientation for my current task",
        description = "Assembled from the project's own material, segmented by step and cited " +
            "throughout. Having no current task, and having a task the corpus cannot ground a " +
            "packet for, are both ordinary states rather than errors — the response says which, " +
            "and no packet is ever invented to fill the gap. Reading this is never a prerequisite " +
            "for starting the task, and never assigns one.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Orientation state returned"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "404", description = "You are not a member of that project"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/me/orientation")
    @PreAuthorize("hasAnyRole('USER', 'PM', 'HR', 'ADMIN')")
    suspend fun getMyOrientation(
        @Parameter(hidden = true)
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam projectId: UUID,
    ): MyOrientationResponse = taskOrientationService.getForHire(resolveUserId(jwt), projectId)

    private fun resolveUserId(jwt: Jwt): UUID =
        userApi.getUserIdByAuthId(jwt.subject).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: ${jwt.subject}")
        }
}
