package com.sprintstart.sprintstartbackend.onboarding.repository

import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingPath
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface OnboardingPathRepository : JpaRepository<OnboardingPath, UUID> {
    // Onboarding is per-project: a user has at most one path per project, so path lookup is keyed
    // by (userId, projectId). The bare-userId finders below return every project's path for a user
    // and are used only where a project context is not available (team overview, admin sweeps).

    fun findByUserIdAndProjectId(userId: UUID, projectId: UUID): Optional<OnboardingPath>

    fun deleteByUserIdAndProjectId(userId: UUID, projectId: UUID)

    fun existsByUserIdAndProjectId(userId: UUID, projectId: UUID): Boolean

    // Deletes every path a user owns across all projects -- the admin "reset this user's
    // onboarding" operation, distinct from the per-project delete a user triggers themselves.
    fun deleteByUserId(userId: UUID)

    fun findByUserId(userId: UUID): List<OnboardingPath>

    fun findByUserIdIn(userIds: Collection<UUID>): List<OnboardingPath>

    fun findByUserIdInAndProjectId(userIds: Collection<UUID>, projectId: UUID): List<OnboardingPath>
}
