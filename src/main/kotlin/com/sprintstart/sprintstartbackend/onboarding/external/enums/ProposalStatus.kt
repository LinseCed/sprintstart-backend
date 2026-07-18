package com.sprintstart.sprintstartbackend.onboarding.external.enums

/**
 * Lifecycle state of an AI-proposed [com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyProposal],
 * [com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdgeProposal], or
 * per-item [com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStep] review.
 *
 * [PROPOSED] awaits PM review. [APPROVED] means a real graph element was created from it
 * (see [com.sprintstart.sprintstartbackend.onboarding.service.CompetencyProposalService]) or,
 * for a [com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStep], that the PM
 * signed off on it; [REJECTED] is terminal and, for a step, excludes it from what a `Blueprint`
 * actually contributes once its wire schema is built for the AI service. Unlike
 * [com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStatus] (the *whole*
 * blueprint's own lifecycle), there is no ARCHIVED/rollback state here.
 */
enum class ProposalStatus { PROPOSED, APPROVED, REJECTED }
