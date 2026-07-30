package com.sprintstart.sprintstartbackend.onboarding.repository

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.StarterWorkTaskProposal
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StarterWorkTaskProposalRepository : JpaRepository<StarterWorkTaskProposal, UUID> {
    /** The live pool nobody has vouched for yet -- what a PM's review surface shows. */
    fun findAllByStatusAndReviewedFalse(status: ProposalStatus): List<StarterWorkTaskProposal>

    fun findAllByStatus(status: ProposalStatus): List<StarterWorkTaskProposal>

    fun findAllByStatusIn(statuses: Collection<ProposalStatus>): List<StarterWorkTaskProposal>

    /** The Task-0-eligible pool: approved tasks a PM flagged as suitable for a hire's first task. */
    fun findAllByStatusAndTaskZeroEligibleTrue(status: ProposalStatus): List<StarterWorkTaskProposal>
}
