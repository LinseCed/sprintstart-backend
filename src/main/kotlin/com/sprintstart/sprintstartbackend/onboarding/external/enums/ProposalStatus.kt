package com.sprintstart.sprintstartbackend.onboarding.external.enums

/**
 * Lifecycle state of an AI-proposed [com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyProposal]
 * or [com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdgeProposal].
 *
 * [PROPOSED] awaits PM review. [APPROVED] means a real graph element was created from it
 * (see [com.sprintstart.sprintstartbackend.onboarding.service.CompetencyProposalService]);
 * [REJECTED] is terminal and never touches the live graph. Unlike
 * [com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStatus], there is no
 * ARCHIVED/rollback state -- the competency graph has no whole-resource replace to roll back.
 */
enum class ProposalStatus { PROPOSED, APPROVED, REJECTED }
