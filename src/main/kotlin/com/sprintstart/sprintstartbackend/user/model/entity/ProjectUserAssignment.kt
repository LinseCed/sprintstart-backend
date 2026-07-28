package com.sprintstart.sprintstartbackend.user.model.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.MapsId
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Embeddable
data class ProjectUserAssignmentId(
    @Column(name = "user_id")
    var userId: UUID = UUID(0L, 0L),
    @Column(name = "project_id")
    var projectId: UUID = UUID(0L, 0L),
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Entity
@Table(name = "user_projects")
class ProjectUserAssignment(
    @EmbeddedId
    val id: ProjectUserAssignmentId = ProjectUserAssignmentId(),
    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,
    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("projectId")
    @JoinColumn(name = "project_id", nullable = false)
    val project: Project,
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "user_project_assignment_roles",
        joinColumns = [
            JoinColumn(
                name = "user_id",
                referencedColumnName = "user_id",
                nullable = false,
                foreignKey = ForeignKey(name = "fk_upar_user_project_user"),
            ),
            JoinColumn(
                name = "project_id",
                referencedColumnName = "project_id",
                nullable = false,
                foreignKey = ForeignKey(name = "fk_upar_user_project_project"),
            ),
        ],
        inverseJoinColumns = [
            JoinColumn(
                name = "role_id",
                nullable = false,
                foreignKey = ForeignKey(name = "fk_upar_role_id"),
            ),
        ],
    )
    var projectRoles: MutableSet<ProjectRole> = mutableSetOf(),
    /**
     * When this person joined this project — the moment onboarding's clock starts.
     *
     * Nullable because assignments made before this column existed have no honest value to
     * backfill: guessing one would put a fabricated number underneath the metric the whole
     * initiative is judged on. A hire with no `assignedAt` is reported as "clock unknown" rather
     * than as instantaneous.
     */
    @Column(name = "assigned_at")
    val assignedAt: Instant? = Instant.now(),
) {
    constructor(user: User, project: Project) : this(
        id = ProjectUserAssignmentId(user.id, project.id),
        user = user,
        project = project,
    )

    /**
     * The roles this person actually holds here: the ones set on this assignment, falling back to
     * the ones set on the user.
     *
     * This codebase has **two** role mechanisms — [projectRoles] on the assignment and
     * [User.projectRoles] on the user — and the precedence between them is defined here, once, so
     * that no two surfaces can answer the question differently. Before this existed they did:
     * `AdminProjectMapper` read the assignment's set alone, which is why the admin project user
     * list showed nobody as holding any role.
     *
     * **The assignment's set currently has no writer anywhere**, so today this always resolves to
     * the user's roles. That is not an argument for deleting the branch — it is the seam that
     * per-project roles would arrive through (somebody a developer on one project and a delivery
     * lead on another, which is the grain role tracks assume), and having one definition means
     * populating it later changes every reader at once instead of some of them. Whether to populate
     * it or drop the mechanism is an open decision; either way it is decided in one place.
     */
    val effectiveProjectRoles: Set<ProjectRole>
        get() = projectRoles.ifEmpty { user.projectRoles }
}
