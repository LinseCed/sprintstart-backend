package com.sprintstart.sprintstartbackend.onboarding.repository

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyProposal
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CompetencyProposalRepository : JpaRepository<CompetencyProposal, UUID> {
    fun findAllByStatus(status: ProposalStatus): List<CompetencyProposal>

    fun findTopByOrderByCreatedAtDesc(): CompetencyProposal?
}
