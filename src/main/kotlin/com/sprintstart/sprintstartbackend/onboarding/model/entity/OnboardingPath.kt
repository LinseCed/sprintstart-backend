package com.sprintstart.sprintstartbackend.onboarding.model.entity

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "onboarding_paths",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_onboarding_paths_user_project",
            columnNames = ["user_id", "project_id"],
        ),
    ],
)
class OnboardingPath(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    // The project this path belongs to. Onboarding is per-project: a user has at most one path
    // per project (uq_onboarding_paths_user_project), and a user in several projects onboards each
    // independently. The path is a disposable projection -- safe to delete and rebuild per project.
    @Column(name = "project_id", nullable = false)
    val projectId: UUID,
    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(nullable = true)
    val blueprintId: UUID? = null,
    @OneToMany(
        mappedBy = "path",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
    )
    @OrderBy("position ASC")
    val phases: MutableList<OnboardingPhase> = mutableListOf(),
)
