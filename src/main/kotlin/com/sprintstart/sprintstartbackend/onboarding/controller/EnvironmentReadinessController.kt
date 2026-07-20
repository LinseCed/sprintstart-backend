package com.sprintstart.sprintstartbackend.onboarding.controller

import com.sprintstart.sprintstartbackend.onboarding.model.request.environment.ReportEnvironmentReadinessRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.MyEnvironmentResponse
import com.sprintstart.sprintstartbackend.onboarding.service.EnvironmentReadinessService
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
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Whether a new hire's environment is up, for one project — the first wall of week one.
 *
 * A hire reads their own readiness and, via the documented command, reports the evidence that
 * settles it. Readiness a hire never reported can still be *derived* from a pull request they
 * authored, so the endpoints answer honestly whether or not the command was ever run.
 *
 * Not-ready is a returned state, never a 403: a hire is not gated out of the product for a setup
 * step they have not finished.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@Tag(
    name = "Onboarding - Environment",
    description = "Environment readiness, settled by evidence rather than self-declaration",
)
class EnvironmentReadinessController(
    private val environmentReadinessService: EnvironmentReadinessService,
    private val userApi: UserApi,
) {
    @Operation(
        summary = "My environment readiness on a project",
        description = "Reported evidence if any, otherwise derived from a pull request I authored, " +
            "otherwise not-ready. Not-ready is a normal response, not an error.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Readiness returned"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "404", description = "You are not a member of that project"),
        ],
    )
    @GetMapping("/me/environment")
    @PreAuthorize("hasAnyRole('USER', 'PM', 'HR', 'ADMIN')")
    fun getMyEnvironment(
        @Parameter(hidden = true)
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam projectId: UUID,
    ): MyEnvironmentResponse =
        environmentReadinessService.getReadiness(resolveUserId(jwt), projectId)

    @Operation(
        summary = "Report that my environment is up",
        description = "Posted by the documented one-liner from the hire's machine: a successful " +
            "build-and-test run, or a green CI run. Idempotent — once ready, a repeat is a no-op.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "Readiness recorded (or already on record)"),
            ApiResponse(responseCode = "400", description = "Derived-only evidence, or a future timestamp"),
            ApiResponse(responseCode = "404", description = "You are not a member of that project"),
        ],
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/me/environment/report")
    @PreAuthorize("hasAnyRole('USER', 'PM', 'HR', 'ADMIN')")
    fun report(
        @Parameter(hidden = true)
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam projectId: UUID,
        @RequestBody request: ReportEnvironmentReadinessRequest,
    ): MyEnvironmentResponse =
        environmentReadinessService.report(
            hireId = resolveUserId(jwt),
            projectId = projectId,
            evidence = request.evidence,
            readyAt = request.readyAt,
            detail = request.detail,
        )

    private fun resolveUserId(jwt: Jwt): UUID =
        userApi.getUserIdByAuthId(jwt.subject).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: ${jwt.subject}")
        }
}
