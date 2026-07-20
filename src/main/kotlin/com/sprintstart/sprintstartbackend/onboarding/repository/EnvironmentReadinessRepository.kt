package com.sprintstart.sprintstartbackend.onboarding.repository

import com.sprintstart.sprintstartbackend.onboarding.model.entity.EnvironmentReadiness
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface EnvironmentReadinessRepository : JpaRepository<EnvironmentReadiness, UUID> {
    fun findByHireIdAndProjectId(hireId: UUID, projectId: UUID): EnvironmentReadiness?
}
