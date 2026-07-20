package com.sprintstart.sprintstartbackend.onboarding.repository

import com.sprintstart.sprintstartbackend.onboarding.model.entity.BuddyContact
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingBuddy
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface OnboardingBuddyRepository : JpaRepository<OnboardingBuddy, UUID> {
    fun findByHireIdAndProjectId(hireId: UUID, projectId: UUID): OnboardingBuddy?

    fun findAllByProjectId(projectId: UUID): List<OnboardingBuddy>

    /** What this person is responsible for — the buddy's own view of their obligations. */
    fun findAllByBuddyId(buddyId: UUID): List<OnboardingBuddy>

    fun deleteByHireIdAndProjectId(hireId: UUID, projectId: UUID)
}

@Repository
interface BuddyContactRepository : JpaRepository<BuddyContact, UUID> {
    fun findAllByHireIdAndProjectIdOrderByOccurredAtDesc(hireId: UUID, projectId: UUID): List<BuddyContact>

    fun findAllByProjectId(projectId: UUID): List<BuddyContact>
}
