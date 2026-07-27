package com.sprintstart.sprintstartbackend.user.service

import com.sprintstart.sprintstartbackend.user.external.ProjectMember
import com.sprintstart.sprintstartbackend.user.external.ProjectMembershipApi
import com.sprintstart.sprintstartbackend.user.model.entity.ProjectUserAssignment
import com.sprintstart.sprintstartbackend.user.repository.ProjectUserAssignmentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Read-only adapter over project assignments for other modules.
 *
 * Deliberately thin: it maps assignments to a boundary type and nothing else. Anything that needs
 * to interpret these facts — what counts as a stall, how long a first response may take — belongs
 * to the module that cares, not here.
 */
@Service
internal class ProjectMembershipApiService(
    private val projectUserAssignmentRepository: ProjectUserAssignmentRepository,
) : ProjectMembershipApi {
    @Transactional(readOnly = true)
    override fun getProjectMembers(projectId: UUID): List<ProjectMember> {
        return projectUserAssignmentRepository.findAllByProjectId(projectId).map { assignment ->
            val user = assignment.user
            ProjectMember(
                userId = user.id,
                displayName = "${user.firstname} ${user.lastname}".trim().ifBlank { user.username },
                githubLogin = user.githubLogin,
                joinedAt = assignment.assignedAt,
                onboardingTrackKey = trackKeyFor(assignment),
            )
        }
    }

    /**
     * The single onboarding track this assignment's roles agree on, or null.
     *
     * Reads the roles held **on this project** first, since that is the right grain: somebody can
     * be a developer on one project and a delivery lead on another. It falls back to the user's
     * global roles only when the assignment carries none, because this codebase has two role
     * mechanisms — `ProjectUserAssignment.projectRoles` and `User.projectRoles` — and which one is
     * populated depends on which surface created the assignment. That duplication predates tracks
     * and is worth resolving on its own; guessing between them here would just hide it.
     *
     * Disagreement resolves to null, not to a winner. Somebody holding two roles with different
     * tracks is a real situation (a PM who also ships code), and picking one arbitrarily would put
     * the wrong vocabulary in front of them. Null lets onboarding fall back to its default, which
     * is the same answer they got before tracks existed.
     */
    private fun trackKeyFor(assignment: ProjectUserAssignment): String? {
        val roles = assignment.projectRoles.ifEmpty { assignment.user.projectRoles }
        return roles.mapNotNull { it.onboardingTrackKey }.distinct().singleOrNull()
    }
}
