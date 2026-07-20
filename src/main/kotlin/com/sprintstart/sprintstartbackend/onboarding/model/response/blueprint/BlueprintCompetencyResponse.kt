package com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import java.util.UUID

/**
 * One competency selected into a baseline, as the PM review surface sees it.
 *
 * [label]/[description] are joined in from the competency graph rather than stored on the entry:
 * the entry owns only the selection ([competencyKey], [targetLevel], [requirement]), so a
 * renamed competency reads correctly here without touching any baseline.
 */
data class BlueprintCompetencyResponse(
    val competencyKey: String,
    val label: String,
    val description: String? = null,
    // The bar for this scope. Resolved: the entry's own override when it has one, otherwise the
    // competency's target level, so the reviewer always sees the level that will actually apply.
    val targetLevel: Int,
    // True when [targetLevel] is this baseline's own override rather than the graph's bar.
    val targetLevelOverridden: Boolean,
    val requirement: String,
    val invariant: Boolean,
    // Why the proposer put this competency in the baseline, when it said.
    val rationale: String? = null,
    // The BlueprintCompetency entity id, targeted by
    // POST .../blueprints/competencies/{proposalId}/approve|reject.
    val proposalId: UUID,
    val status: ProposalStatus,
)
