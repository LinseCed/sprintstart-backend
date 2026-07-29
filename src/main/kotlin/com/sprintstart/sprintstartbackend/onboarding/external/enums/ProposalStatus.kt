package com.sprintstart.sprintstartbackend.onboarding.external.enums

/**
 * Lifecycle state of an AI-mined
 * [com.sprintstart.sprintstartbackend.onboarding.model.entity.StarterWorkTaskProposal].
 *
 * [PROPOSED] awaits PM review; [APPROVED] makes the task claimable; [REJECTED] is terminal.
 *
 * It used to serve competency and edge proposals and per-item blueprint review too. Those are all
 * gone — competencies are authored directly rather than proposed, and the baseline was retired —
 * so starter work is the one proposal lifecycle left.
 */
enum class ProposalStatus { PROPOSED, APPROVED, REJECTED }
