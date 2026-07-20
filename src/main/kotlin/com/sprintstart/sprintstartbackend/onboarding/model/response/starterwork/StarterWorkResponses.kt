package com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.TaskType
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

/**
 * One pool task ranked for one hire.
 *
 * [reasons] is not decoration: matching is a *suggestion*, and a suggestion nobody can interrogate
 * is an instruction. Each entry is one clause the client can render as "suggested because it …",
 * strongest signal first, with any responsiveness warning last. An empty list means nothing matched
 * and the task is only in the list because it is available — which is worth saying plainly rather
 * than dressing up with an invented reason.
 */
data class RankedStarterWorkTaskResponse(
    val task: StarterWorkTaskProposalResponse,
    val score: Double,
    val matchedCompetencyKeys: List<String>,
    val taskType: TaskType,
    val reasons: List<String>,
)
