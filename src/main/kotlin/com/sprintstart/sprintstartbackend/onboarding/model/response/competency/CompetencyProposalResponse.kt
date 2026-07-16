package com.sprintstart.sprintstartbackend.onboarding.model.response.competency

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import java.util.UUID

data class CompetencyProposalResponse(
    val id: UUID,
    val key: String,
    val label: String,
    val description: String?,
    val kind: CompetencyKind,
    val repoRef: String?,
    val status: ProposalStatus,
)

data class CompetencyEdgeProposalResponse(
    val id: UUID,
    val fromKey: String,
    val toKey: String,
    val kind: EdgeKind,
    val rationale: String?,
    val status: ProposalStatus,
)

/**
 * The competencies and edges currently awaiting PM review (status PROPOSED).
 */
data class ProposedCompetencyGraphResponse(
    val competencies: List<CompetencyProposalResponse>,
    val edges: List<CompetencyEdgeProposalResponse>,
)

data class GenerateCompetencyGraphResponse(
    val status: String,
    val competenciesProposed: Int,
    val edgesProposed: Int,
    val notes: List<String>,
)
