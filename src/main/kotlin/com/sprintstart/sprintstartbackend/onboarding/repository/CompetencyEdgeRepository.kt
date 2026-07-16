package com.sprintstart.sprintstartbackend.onboarding.repository

import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CompetencyEdgeRepository : JpaRepository<CompetencyEdge, UUID> {
    fun findAllByToKey(toKey: String): List<CompetencyEdge>

    fun findAllByFromKey(fromKey: String): List<CompetencyEdge>

    fun existsByFromKeyAndToKeyAndKind(fromKey: String, toKey: String, kind: EdgeKind): Boolean
}
