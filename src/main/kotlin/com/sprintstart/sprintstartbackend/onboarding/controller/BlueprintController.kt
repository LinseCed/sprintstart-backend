package com.sprintstart.sprintstartbackend.onboarding.controller

import com.sprintstart.sprintstartbackend.onboarding.model.request.blueprint.ApproveBlueprintRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.blueprint.GenerateBlueprintsRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.blueprint.RejectBlueprintCompetencyRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.blueprint.RejectBlueprintRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.blueprint.RollbackBlueprintRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintCompetencyResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.GenerateBlueprintsResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.ProposedBlueprintsResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.VersionListResponse
import com.sprintstart.sprintstartbackend.onboarding.service.BlueprintService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/onboarding/blueprints")
@Tag(name = "Onboarding - Blueprints", description = "Manage AI-generated onboarding blueprints")
class BlueprintController(
    private val blueprintService: BlueprintService,
) {
    /**
     * Triggers AI blueprint generation for the given scopes.
     *
     * Generated blueprints are stored as PROPOSED proposals awaiting PM approval — the current
     * ACTIVE baseline for a scope is left untouched until a proposal is explicitly approved.
     * If no scopes are provided, all known scopes are generated.
     */
    @Operation(summary = "Generate blueprints", description = "Triggers AI blueprint generation for the given scopes")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Blueprint generation outcomes returned"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    suspend fun generateBlueprints(
        @RequestBody request: GenerateBlueprintsRequest,
    ): GenerateBlueprintsResponse {
        return blueprintService.generateBlueprints(request.scopes)
    }

    /**
     * Returns all archived blueprint versions for the given scope.
     */
    @Operation(summary = "List versions", description = "Returns all archived blueprint versions for a scope")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Version list returned"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
            ApiResponse(responseCode = "404", description = "No blueprint found for the given scope"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/{scope}/versions")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    suspend fun listVersions(
        @PathVariable scope: String,
    ): VersionListResponse {
        return blueprintService.listVersions(scope)
    }

    /**
     * Restores a previous blueprint version for the given scope, replacing the active blueprint.
     */
    @Operation(summary = "Rollback blueprint", description = "Restores a previous blueprint version")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Blueprint rolled back and active blueprint returned"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
            ApiResponse(responseCode = "404", description = "No blueprint or version found for the given scope"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/{scope}/rollback")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    suspend fun rollback(
        @PathVariable scope: String,
        @RequestBody request: RollbackBlueprintRequest,
    ): BlueprintResponse {
        return blueprintService.rollback(scope, request.version)
    }

    /**
     * Lists the blueprints awaiting PM review, optionally filtered by scope.
     */
    @Operation(
        summary = "List proposed blueprints",
        description = "Returns blueprints in PROPOSED state, optionally filtered by scope",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Proposed blueprints returned"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/proposed")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    fun listProposed(
        @RequestParam(required = false) scope: String?,
    ): ProposedBlueprintsResponse {
        return blueprintService.listProposed(scope)
    }

    /**
     * Approves a proposed blueprint version, activating it as the new baseline for the scope.
     */
    @Operation(
        summary = "Approve blueprint",
        description = "Approves a proposed blueprint version and makes it the active baseline",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Blueprint approved and active blueprint returned"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
            ApiResponse(responseCode = "404", description = "No proposed blueprint found for the scope and version"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/{scope}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    fun approve(
        @PathVariable scope: String,
        @RequestBody request: ApproveBlueprintRequest,
    ): BlueprintResponse {
        return blueprintService.approve(scope, request.version)
    }

    /**
     * Rejects a proposed blueprint version, archiving it without touching the active baseline.
     */
    @Operation(
        summary = "Reject blueprint",
        description = "Rejects a proposed blueprint version and archives it",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Blueprint rejected and archived blueprint returned"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
            ApiResponse(responseCode = "404", description = "No proposed blueprint found for the scope and version"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/{scope}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    fun reject(
        @PathVariable scope: String,
        @RequestBody request: RejectBlueprintRequest,
    ): BlueprintResponse {
        return blueprintService.reject(scope, request.version, request.reason)
    }

    /**
     * Approves one competency within a proposed baseline, independent of the whole baseline's own
     * approve/reject decision.
     */
    @Operation(
        summary = "Approve a baseline competency",
        description = "Approves one competency within a proposed baseline",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Competency approved"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
            ApiResponse(responseCode = "404", description = "No baseline competency found with the given id"),
            ApiResponse(responseCode = "409", description = "Competency was already decided"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/competencies/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    fun approveCompetency(
        @PathVariable id: UUID,
    ): BlueprintCompetencyResponse {
        return blueprintService.approveCompetency(id)
    }

    /**
     * Rejects one competency within a proposed baseline, excluding it from the baseline going
     * forward -- it stops being something everyone in the scope must reach.
     */
    @Operation(
        summary = "Reject a baseline competency",
        description = "Rejects one competency within a proposed baseline",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Competency rejected"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
            ApiResponse(responseCode = "404", description = "No baseline competency found with the given id"),
            ApiResponse(
                responseCode = "409",
                description = "Competency was already decided, or is invariant and cannot be rejected",
            ),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/competencies/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    fun rejectCompetency(
        @PathVariable id: UUID,
        @RequestBody request: RejectBlueprintCompetencyRequest,
    ): BlueprintCompetencyResponse {
        return blueprintService.rejectCompetency(id, request.reason)
    }
}
