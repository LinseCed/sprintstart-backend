package com.sprintstart.sprintstartbackend.onboarding.controller

import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.model.request.competency.CreateCompetencyEdgeRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.competency.UpdateCompetencyRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyEdgeResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.DeleteCompetencyResponse
import com.sprintstart.sprintstartbackend.onboarding.service.CompetencyGraphAuthoringService
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * A PM authoring the live competency graph directly, as opposed to
 * [CompetencyGraphProposalController]'s review queue for what the AI proposed.
 *
 * Split from that controller because the two are genuinely different jobs: one decides what
 * enters the graph, this one changes what is already in it. `HR` can review proposals but not
 * author, so every endpoint here is `ADMIN`/`PM`.
 */
@RestController
@RequestMapping("/api/v1/onboarding/competency-graph")
@Tag(
    name = "Onboarding - Competency Graph Authoring",
    description = "Edit, remove and connect competencies in the live graph",
)
class CompetencyGraphAuthoringController(
    private val competencyGraphAuthoringService: CompetencyGraphAuthoringService,
) {
    /**
     * Reads one live competency, including the fields the projected path omits.
     */
    @Operation(
        summary = "Read a live competency",
        description = "Returns one live competency's full authoring detail, including description and target level",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Competency returned"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
            ApiResponse(responseCode = "404", description = "No live competency found with the given key"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/competencies/{key}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    fun getCompetency(@PathVariable key: String): CompetencyResponse {
        return competencyGraphAuthoringService.getCompetency(key)
    }

    /**
     * Applies a PM's edit to a live competency node.
     *
     * The competency's `key` is not editable — it is the identity every ledger row, edge and
     * module points at. The label is what a PM renames.
     */
    @Operation(
        summary = "Edit a live competency",
        description = "Updates a live competency's label, description, kind, target level or invariant flag",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Competency updated"),
            ApiResponse(responseCode = "400", description = "Target level outside 1..4, or blank label"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
            ApiResponse(responseCode = "404", description = "No live competency found with the given key"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @PutMapping("/competencies/{key}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    fun updateCompetency(
        @PathVariable key: String,
        @RequestBody request: UpdateCompetencyRequest,
    ): CompetencyResponse {
        return competencyGraphAuthoringService.updateCompetency(key, request)
    }

    /**
     * Removes a competency and every edge touching it from the live graph.
     *
     * The node leaves every hire's path, but nobody loses a competency they already earned: the
     * ledger is independent of graph visibility, and this records the removal rather than
     * deleting anything.
     */
    @Operation(
        summary = "Remove a competency from the graph",
        description = "Removes a competency and its edges from the live graph; earned ledger entries are kept",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Competency removed from the graph"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
            ApiResponse(responseCode = "404", description = "No live competency found with the given key"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @DeleteMapping("/competencies/{key}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    fun deleteCompetency(@PathVariable key: String): DeleteCompetencyResponse {
        return competencyGraphAuthoringService.deleteCompetency(key)
    }

    /**
     * Adds a hand-authored edge between two live competencies.
     */
    @Operation(
        summary = "Add a competency edge",
        description = "Adds a hand-authored edge between two live competencies",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Edge created"),
            ApiResponse(responseCode = "400", description = "Self-edge, or the edge would create a prerequisite cycle"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
            ApiResponse(responseCode = "404", description = "An endpoint is not a live competency"),
            ApiResponse(responseCode = "409", description = "The edge already exists"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/edges")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    fun createEdge(@RequestBody request: CreateCompetencyEdgeRequest): CompetencyEdgeResponse {
        return competencyGraphAuthoringService.createEdge(request)
    }

    /**
     * Removes one edge from the live graph, leaving both endpoints in place.
     */
    @Operation(
        summary = "Remove a competency edge",
        description = "Removes one edge from the live graph; both endpoint competencies are kept",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Edge removed"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
            ApiResponse(responseCode = "404", description = "No such edge in the live graph"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @DeleteMapping("/edges")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    fun deleteEdge(
        @RequestParam fromKey: String,
        @RequestParam toKey: String,
        @RequestParam(defaultValue = "PREREQUISITE") kind: EdgeKind,
    ): CompetencyEdgeResponse {
        return competencyGraphAuthoringService.deleteEdge(fromKey, toKey, kind)
    }
}
