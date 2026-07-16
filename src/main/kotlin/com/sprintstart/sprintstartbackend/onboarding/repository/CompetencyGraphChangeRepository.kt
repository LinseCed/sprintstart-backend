package com.sprintstart.sprintstartbackend.onboarding.repository

import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphChange
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CompetencyGraphChangeRepository : JpaRepository<CompetencyGraphChange, UUID> {
    fun findAllByVersion(version: Int): List<CompetencyGraphChange>

    fun findAllByVersionGreaterThanOrderByVersionAsc(version: Int): List<CompetencyGraphChange>
}
