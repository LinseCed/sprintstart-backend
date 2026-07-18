package com.sprintstart.sprintstartbackend.onboarding.repository

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.StarterWorkTaskProposal
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StarterWorkTaskProposalRepository : JpaRepository<StarterWorkTaskProposal, UUID> {
    fun findAllByStatus(status: ProposalStatus): List<StarterWorkTaskProposal>

    fun findAllByStatusIn(statuses: Collection<ProposalStatus>): List<StarterWorkTaskProposal>
}
