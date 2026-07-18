package com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import java.util.UUID

data class BlueprintStepResponse(
    val id: String? = null,
    val title: String,
    val description: String? = null,
    val requirement: String = "recommended",
    val invariant: Boolean = false,
    // The real BlueprintStep entity id -- distinct from `id` above (the semantic stepId from
    // generation) -- targeted by POST .../blueprints/steps/{proposalId}/approve|reject.
    val proposalId: UUID,
    val status: ProposalStatus,
)
