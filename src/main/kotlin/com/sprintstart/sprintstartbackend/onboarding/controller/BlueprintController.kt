package com.sprintstart.sprintstartbackend.onboarding.controller

import com.sprintstart.sprintstartbackend.onboarding.model.request.blueprint.GenerateBlueprintsRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.blueprint.RollbackBlueprintRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintDiffResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.DraftListResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.GenerateBlueprintsResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.VersionListResponse
import com.sprintstart.sprintstartbackend.onboarding.service.BlueprintService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/onboarding/blueprints")
@Tag(name = "Onboarding - Blueprints", description = "Manage AI-generated onboarding blueprints")
class BlueprintController(
    private val blueprintService: BlueprintService,
) {
    /**
     * Triggers AI blueprint generation for the given scopes.
     *
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
     * Returns all pending blueprint drafts awaiting review.
     */
    @Operation(summary = "List drafts", description = "Lists all pending blueprint drafts")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Draft list returned"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/drafts")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    suspend fun listDrafts(): DraftListResponse {
        return blueprintService.listDrafts()
    }

    /**
     * Returns the diff between the draft and the active blueprint for the given scope.
     */
    @Operation(summary = "Get draft diff", description = "Returns the diff for a specific draft scope")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Diff returned"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
            ApiResponse(responseCode = "404", description = "No draft found for the given scope"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/drafts/{scope}/diff")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    suspend fun getDraftDiff(
        @PathVariable scope: String,
    ): BlueprintDiffResponse {
        return blueprintService.getDraftDiff(scope)
    }

    /**
     * Promotes the draft for the given scope to the active blueprint.
     */
    @Operation(summary = "Approve draft", description = "Promotes a draft to the active blueprint")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Draft approved and active blueprint returned"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
            ApiResponse(responseCode = "404", description = "No draft found for the given scope"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/drafts/{scope}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    suspend fun approveDraft(
        @PathVariable scope: String,
    ): BlueprintResponse {
        return blueprintService.approveDraft(scope)
    }

    /**
     * Deletes the pending draft for the given scope without promoting it.
     */
    @Operation(summary = "Discard draft", description = "Deletes a pending draft")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "Draft discarded"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
            ApiResponse(responseCode = "404", description = "No draft found for the given scope"),
        ],
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/drafts/{scope}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    suspend fun discardDraft(
        @PathVariable scope: String,
    ) {
        blueprintService.discardDraft(scope)
    }

    /**
     * Returns all stored blueprint versions for the given scope.
     */
    @Operation(summary = "List versions", description = "Returns all blueprint versions for a scope")
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
}
