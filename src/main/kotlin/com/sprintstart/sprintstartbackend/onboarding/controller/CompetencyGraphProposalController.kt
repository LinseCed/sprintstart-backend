package com.sprintstart.sprintstartbackend.onboarding.controller

import com.sprintstart.sprintstartbackend.onboarding.model.request.competency.RejectProposalRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyEdgeProposalResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyProposalResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.GenerateCompetencyGraphResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.ProposedCompetencyGraphResponse
import com.sprintstart.sprintstartbackend.onboarding.service.CompetencyProposalService
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
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/onboarding/competency-graph")
@Tag(name = "Onboarding - Competency Graph", description = "Manage AI-proposed competency graph nodes and edges")
class CompetencyGraphProposalController(
    private val competencyProposalService: CompetencyProposalService,
) {
    /**
     * Triggers AI competency graph proposal generation over the ingested corpus.
     *
     * Generated competencies/edges are stored as PROPOSED, one row each, awaiting individual PM
     * review — the live graph is left untouched until a proposal is explicitly approved.
     */
    @Operation(
        summary = "Generate competency graph proposals",
        description = "Triggers AI competency graph proposal generation over the ingested corpus",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Proposal generation outcome returned"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    suspend fun generate(): GenerateCompetencyGraphResponse {
        return competencyProposalService.generate()
    }

    /**
     * Lists the competencies and edges currently awaiting PM review.
     */
    @Operation(
        summary = "List proposed competency graph elements",
        description = "Returns competencies and edges in PROPOSED state",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Proposed graph elements returned"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/proposed")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    fun listProposed(): ProposedCompetencyGraphResponse {
        return competencyProposalService.listProposed()
    }

    /**
     * Approves a proposed competency, creating it as a real node in the live graph.
     */
    @Operation(
        summary = "Approve a proposed competency",
        description = "Approves a proposed competency, creating it in the live graph",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Competency approved and created"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
            ApiResponse(responseCode = "404", description = "No proposed competency found with the given id"),
            ApiResponse(responseCode = "409", description = "Proposal is no longer PROPOSED"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/competencies/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    fun approveCompetency(@PathVariable id: UUID): CompetencyProposalResponse {
        return competencyProposalService.approveCompetency(id)
    }

    /**
     * Rejects a proposed competency without touching the live graph.
     */
    @Operation(
        summary = "Reject a proposed competency",
        description = "Rejects a proposed competency; the live graph is left untouched",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Competency rejected"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
            ApiResponse(responseCode = "404", description = "No proposed competency found with the given id"),
            ApiResponse(responseCode = "409", description = "Proposal is no longer PROPOSED"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/competencies/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    fun rejectCompetency(
        @PathVariable id: UUID,
        @RequestBody(required = false) request: RejectProposalRequest?,
    ): CompetencyProposalResponse {
        return competencyProposalService.rejectCompetency(id, request?.reason)
    }

    /**
     * Approves a proposed prerequisite edge, creating it in the live graph.
     */
    @Operation(
        summary = "Approve a proposed competency edge",
        description = "Approves a proposed prerequisite edge, creating it in the live graph",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Edge approved and created"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
            ApiResponse(responseCode = "404", description = "No proposed edge found with the given id"),
            ApiResponse(
                responseCode = "409",
                description = "Proposal is no longer PROPOSED, or an endpoint is not yet a live competency",
            ),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/edges/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    fun approveEdge(@PathVariable id: UUID): CompetencyEdgeProposalResponse {
        return competencyProposalService.approveEdge(id)
    }

    /**
     * Rejects a proposed prerequisite edge without touching the live graph.
     */
    @Operation(
        summary = "Reject a proposed competency edge",
        description = "Rejects a proposed prerequisite edge; the live graph is left untouched",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Edge rejected"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role"),
            ApiResponse(responseCode = "404", description = "No proposed edge found with the given id"),
            ApiResponse(responseCode = "409", description = "Proposal is no longer PROPOSED"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/edges/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'PM')")
    fun rejectEdge(
        @PathVariable id: UUID,
        @RequestBody(required = false) request: RejectProposalRequest?,
    ): CompetencyEdgeProposalResponse {
        return competencyProposalService.rejectEdge(id, request?.reason)
    }
}
