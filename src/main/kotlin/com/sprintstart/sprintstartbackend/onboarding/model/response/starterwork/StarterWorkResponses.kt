package com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import java.util.UUID

data class StarterWorkTaskProposalResponse(
    val id: UUID,
    val sourceId: String,
    val title: String,
    val summary: String?,
    val rationale: String?,
    val sourceUrl: String?,
    val competencyKeys: List<String>,
    val status: ProposalStatus,
    /** True when a PM has flagged this approved task as suitable for Task 0. */
    val taskZeroEligible: Boolean,
)

/**
 * The starter-work tasks currently awaiting PM review (status PROPOSED).
 */
data class ProposedStarterWorkResponse(
    val tasks: List<StarterWorkTaskProposalResponse>,
)

data class GenerateStarterWorkResponse(
    val status: String,
    val tasksProposed: Int,
    val notes: List<String>,
)

data class RankedStarterWorkTaskResponse(
    val task: StarterWorkTaskProposalResponse,
    val score: Double,
    val matchedCompetencyKeys: List<String>,
)
