package com.sprintstart.sprintstartbackend.onboarding.controller

import com.sprintstart.sprintstartbackend.onboarding.model.request.buddy.AssignBuddyRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.buddy.LogBuddyContactRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.BuddyAssignmentResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.MyBuddyResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.MyMenteesResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.ProjectAttentionResponse
import com.sprintstart.sprintstartbackend.onboarding.service.OnboardingBuddyService
import com.sprintstart.sprintstartbackend.user.external.UserApi
import io.swagger.v3.oas.annotations.Operation
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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Who a new hire can ask, and who has been left waiting.
 *
 * Split across two audiences: a hire reads their own buddy and logs a conversation; whoever runs
 * the project assigns buddies and works the attention list. Logging a contact is open to both
 * sides on purpose — requiring the mentor to record it would make the quieter half of the
 * relationship invisible.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@Tag(
    name = "Onboarding - Buddies",
    description = "Buddy assignment, contact logging, and who needs a human today",
)
class OnboardingBuddyController(
    private val onboardingBuddyService: OnboardingBuddyService,
    private val userApi: UserApi,
) {
    @Operation(
        summary = "My buddy on a project",
        description = "Who to ask, when you last spoke, and whether that is overdue.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Buddy returned"),
            ApiResponse(responseCode = "204", description = "No buddy assigned on this project"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
        ],
    )
    @GetMapping("/me/buddy")
    @PreAuthorize("hasAnyRole('USER', 'PM', 'HR', 'ADMIN')")
    fun getMyBuddy(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam projectId: UUID,
    ): MyBuddyResponse? = onboardingBuddyService.getBuddyFor(resolveUserId(jwt), projectId)

    @Operation(
        summary = "The hires counting on me",
        description = "Everyone I am the buddy for, worst first: a review kept waiting, a cadence " +
            "gone quiet, a stall. The other side of /me/buddy — so the person who actually closes " +
            "the loop can see who is waiting, not only the hire and the project lead.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Mentees returned (empty when I mentor nobody)"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/me/mentees")
    @PreAuthorize("hasAnyRole('USER', 'PM', 'HR', 'ADMIN')")
    fun getMyMentees(
        @AuthenticationPrincipal jwt: Jwt,
    ): MyMenteesResponse = onboardingBuddyService.getMenteesFor(resolveUserId(jwt))

    @Operation(
        summary = "Log a conversation with my buddy",
        description = "Either side may record it. Nothing verifies it: making people prove they " +
            "spoke costs more than it is worth and produces a record they resent keeping.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "Contact recorded"),
            ApiResponse(responseCode = "400", description = "The contact is dated in the future"),
            ApiResponse(responseCode = "404", description = "That hire is not a member of the project"),
        ],
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/me/buddy/contacts")
    @PreAuthorize("hasAnyRole('USER', 'PM', 'HR', 'ADMIN')")
    fun logMyContact(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam projectId: UUID,
        @RequestBody request: LogBuddyContactRequest,
    ) {
        val me = resolveUserId(jwt)
        onboardingBuddyService.logContact(
            projectId = projectId,
            // A buddy logging on the hire's behalf names the hire; a hire logging for themselves
            // does not have to.
            hireId = request.hireId ?: me,
            recordedBy = me,
            occurredAt = request.occurredAt,
            note = request.note,
        )
    }

    @Operation(summary = "Buddy assignments on a project")
    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/projects/{projectId}/buddies")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    fun listAssignments(@PathVariable projectId: UUID): List<BuddyAssignmentResponse> =
        onboardingBuddyService.listAssignments(projectId)

    @Operation(
        summary = "Assign a buddy",
        description = "A peer, not their manager: what a hire will admit not knowing depends on who is listening.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Buddy assigned"),
            ApiResponse(responseCode = "400", description = "Somebody cannot be their own buddy"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
            ApiResponse(responseCode = "404", description = "Hire or buddy is not a member of the project"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/projects/{projectId}/buddies")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    fun assign(
        @PathVariable projectId: UUID,
        @RequestBody request: AssignBuddyRequest,
    ): BuddyAssignmentResponse = onboardingBuddyService.assign(
        projectId = projectId,
        hireId = request.hireId,
        buddyId = request.buddyId,
        cadenceTargetDays = request.cadenceTargetDays,
    )

    @Operation(
        summary = "Remove a buddy assignment",
        description = "Conversations already logged are kept — they happened.",
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/projects/{projectId}/buddies/{hireId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    fun unassign(@PathVariable projectId: UUID, @PathVariable hireId: UUID) {
        onboardingBuddyService.unassign(projectId, hireId)
    }

    @Operation(
        summary = "Who needs a human today",
        description = "Blocked before drifting, longest wait first. Whether an item is somebody " +
            "else's move is stated, because a hire waiting on a review cannot fix it themselves.",
    )
    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/projects/{projectId}/attention")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    fun getAttention(@PathVariable projectId: UUID): ProjectAttentionResponse =
        onboardingBuddyService.getAttention(projectId)

    private fun resolveUserId(jwt: Jwt): UUID =
        userApi.getUserIdByAuthId(jwt.subject).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: ${jwt.subject}")
        }
}
