package com.sprintstart.sprintstartbackend.onboarding.controller

import com.sprintstart.sprintstartbackend.onboarding.model.request.verification.SubmitVerificationAttemptRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.verification.UpsertVerificationRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.verification.SubmitVerificationAttemptResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.verification.VerificationResponse
import com.sprintstart.sprintstartbackend.onboarding.service.VerificationService
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
 * Exposes a graph node's "Verify" zone: the learner-facing check and its admin configuration.
 *
 * A step's [com.sprintstart.sprintstartbackend.onboarding.model.entity.Verification] gates
 * completion once configured -- submitting an attempt is the only way to finish such a step, and
 * passing writes the durable competency ledger, unlocking dependents on the graph.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@Tag(
    name = "Onboarding - Verification",
    description = "Take, configure, and grade a graph node's verification check",
)
class VerificationController(
    private val verificationService: VerificationService,
) {
//  ========================== Endpoints for users (/me/...) ==========================

    /**
     * Returns the verification config for a step in the authenticated user's path.
     *
     * @param jwt Authenticated JWT used to resolve the current user.
     * @param stepId Identifier of the step whose verification should be loaded.
     * @return The verification prompt, without the rubric or canonical answer.
     */
    @Operation(
        summary = "Get current user's step verification",
        description = "Returns the verification config for a step. The rubric and canonical answer are " +
            "never exposed; grading happens server-side.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Verification returned successfully"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role to access this verification"),
            ApiResponse(
                responseCode = "404",
                description = "No user, step, or verification found for the authenticated user",
            ),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/me/steps/{stepId}/verification")
    @PreAuthorize("hasRole('USER')")
    fun getVerificationForMe(
        @Parameter(hidden = true)
        @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "UUID of the step whose verification should be returned")
        @PathVariable stepId: UUID,
    ): VerificationResponse {
        return verificationService.getVerificationForMe(jwt.subject, stepId)
    }

    /**
     * Submits the authenticated user's answer for a step's verification.
     *
     * Works whether or not the step's lesson was ever started -- this is what makes "test-out"
     * fall out of the normal submission path. On pass, the step is finished and the competency
     * ledger is updated, unlocking dependents. On fail, an escalating hint is returned.
     *
     * @param jwt Authenticated JWT used to resolve the current user.
     * @param stepId Identifier of the step being verified.
     * @param request The submitted answer.
     * @return The graded attempt.
     */
    @Operation(
        summary = "Submit current user's verification attempt",
        description = "Grades the submitted answer -- locally for EXACT/ATTEST, via the AI service for " +
            "KNOWLEDGE. On pass, writes the competency ledger and finishes the step; on fail, returns an " +
            "escalating hint.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "Attempt graded and stored successfully"),
            ApiResponse(responseCode = "400", description = "The step is already finished or skipped"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role to submit this verification"),
            ApiResponse(
                responseCode = "404",
                description = "No user, step, or verification found for the authenticated user",
            ),
            ApiResponse(responseCode = "503", description = "AI grading is temporarily unavailable"),
        ],
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/me/steps/{stepId}/verification/attempts")
    @PreAuthorize("hasRole('USER')")
    suspend fun submitVerificationAttemptForMe(
        @Parameter(hidden = true)
        @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "UUID of the step being verified")
        @PathVariable stepId: UUID,
        @Valid @RequestBody request: SubmitVerificationAttemptRequest,
    ): SubmitVerificationAttemptResponse {
        return verificationService.submitAttemptForMe(jwt.subject, stepId, request)
    }

//  ========================== Endpoints for admins ==========================

    /**
     * Creates or replaces the verification config for a step.
     *
     * @param stepId Identifier of the step whose verification should be configured.
     * @param request The verification config.
     * @return The stored verification config.
     */
    @Operation(
        summary = "Configure a step's verification",
        description = "Creates or replaces the verification config for a step. KNOWLEDGE needs a rubric, " +
            "EXACT needs a canonicalAnswer.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Verification saved successfully"),
            ApiResponse(responseCode = "400", description = "A type-required grading field is missing"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role to configure this verification"),
            ApiResponse(responseCode = "404", description = "No step or referenced competency found"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @PutMapping("/steps/{stepId}/verification")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    fun upsertVerification(
        @Parameter(description = "UUID of the step whose verification should be configured")
        @PathVariable stepId: UUID,
        @Valid @RequestBody request: UpsertVerificationRequest,
    ): VerificationResponse {
        return verificationService.upsertVerification(stepId, request)
    }

    /**
     * Triggers AI lesson synthesis for a step's verification and persists the grounded result.
     *
     * A no-op when the AI service reports the corpus is unchanged since the last synthesis.
     *
     * @param stepId Identifier of the step whose lesson content should be (re)synthesized.
     */
    @Operation(
        summary = "Synthesize a step's lesson content",
        description = "Calls the AI service to synthesize grounded lesson content for the step's " +
            "verification competency/level and persists it. A no-op if the corpus is unchanged since " +
            "the last synthesis.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "Synthesis attempted (content updated or left unchanged)"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role to trigger synthesis"),
            ApiResponse(responseCode = "404", description = "No step, verification, or referenced competency found"),
        ],
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/steps/{stepId}/verification/synthesize-content")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    suspend fun synthesizeContent(
        @Parameter(description = "UUID of the step whose lesson content should be synthesized")
        @PathVariable stepId: UUID,
    ) {
        verificationService.synthesizeContent(stepId)
    }
}
