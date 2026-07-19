package com.sprintstart.sprintstartbackend.onboarding.repository

import com.sprintstart.sprintstartbackend.onboarding.model.entity.Blueprint
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BlueprintRepository : JpaRepository<Blueprint, UUID> {
    fun findAllByStatus(status: BlueprintStatus): List<Blueprint>

    fun findByScopeAndStatus(scope: String, status: BlueprintStatus): Blueprint?

    fun findAllByScopeAndStatus(scope: String, status: BlueprintStatus): List<Blueprint>

    fun findByScopeAndStatusAndVersion(scope: String, status: BlueprintStatus, version: String): Blueprint?

    // Per-project lookups: a blueprint belongs to a project (or is a legacy/global blueprint with a
    // null project). Personalization resolves a project's own baseline first and falls back to the
    // unscoped one when the project has none of its own -- see OnboardingPersonalizationService.

    fun findByProjectIdAndScopeAndStatus(projectId: UUID, scope: String, status: BlueprintStatus): Blueprint?

    fun findByProjectIdIsNullAndScopeAndStatus(scope: String, status: BlueprintStatus): Blueprint?
}
