package com.sprintstart.sprintstartbackend.onboarding.repository

import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphVersion
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CompetencyGraphVersionRepository : JpaRepository<CompetencyGraphVersion, UUID> {
    fun findTopByOrderByVersionDesc(): CompetencyGraphVersion?

    fun findAllByVersionGreaterThanOrderByVersionAsc(version: Int): List<CompetencyGraphVersion>
}
