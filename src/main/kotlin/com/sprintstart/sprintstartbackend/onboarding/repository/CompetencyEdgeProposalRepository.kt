package com.sprintstart.sprintstartbackend.onboarding.repository

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdgeProposal
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CompetencyEdgeProposalRepository : JpaRepository<CompetencyEdgeProposal, UUID> {
    fun findAllByStatus(status: ProposalStatus): List<CompetencyEdgeProposal>

    fun findTopByOrderByCreatedAtDesc(): CompetencyEdgeProposal?
}
