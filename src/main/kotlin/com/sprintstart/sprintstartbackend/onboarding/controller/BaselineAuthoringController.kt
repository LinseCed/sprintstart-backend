package com.sprintstart.sprintstartbackend.onboarding.controller

import com.sprintstart.sprintstartbackend.onboarding.model.request.blueprint.SetBaselineEntryRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BaselineResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintCompetencyResponse
import com.sprintstart.sprintstartbackend.onboarding.service.BaselineAuthoringService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * A PM authoring a project's baseline directly on the approved graph — marking a competency
 * "expected here" in one act instead of running a second AI generate → review → approve pass.
 *
 * Writes the project's ACTIVE baseline in place. The AI proposal flow ([BlueprintController]) still
 * exists and coexists; this is the folded, direct path (D1).
 */
@RestController
@RequestMapping("/api/v1/onboarding/blueprints/projects/{projectId}/baseline")
@Tag(
    name = "Onboarding - Baseline authoring",
    description = "Directly mark competencies expected on a project's baseline",
)
class BaselineAuthoringController(
    private val baselineAuthoringService: BaselineAuthoringService,
) {
    @Operation(
        summary = "A project's live baseline",
        description = "The competencies a hire on this project is expected to reach, however they " +
            "were added — directly authored or approved from an AI proposal.",
    )
    @ResponseStatus(HttpStatus.OK)
    @GetMapping
    @PreAuthorize("hasAnyRole('PM', 'HR', 'ADMIN')")
    fun list(@PathVariable projectId: UUID): BaselineResponse =
        BaselineResponse(entries = baselineAuthoringService.listEntries(projectId))

    @Operation(
        summary = "Add or update a baseline entry",
        description = "Marks a competency expected on this project, at a given bar. Creates the " +
            "project's baseline on first write. Idempotent per competency.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Entry set"),
            ApiResponse(responseCode = "400", description = "targetLevel out of range"),
            ApiResponse(responseCode = "404", description = "No such competency in the graph"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @PutMapping("/{competencyKey}")
    @PreAuthorize("hasAnyRole('PM', 'ADMIN')")
    fun setEntry(
        @PathVariable projectId: UUID,
        @PathVariable competencyKey: String,
        @Valid @RequestBody request: SetBaselineEntryRequest,
    ): BlueprintCompetencyResponse =
        baselineAuthoringService.setEntry(projectId, competencyKey, request)

    @Operation(
        summary = "Remove a baseline entry",
        description = "Drops a competency from this project's baseline. Protected mandates " +
            "(invariant entries) cannot be removed.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "Removed (or was not present)"),
            ApiResponse(responseCode = "409", description = "Entry is a protected mandate"),
        ],
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{competencyKey}")
    @PreAuthorize("hasAnyRole('PM', 'ADMIN')")
    fun removeEntry(
        @PathVariable projectId: UUID,
        @PathVariable competencyKey: String,
    ) = baselineAuthoringService.removeEntry(projectId, competencyKey)
}
