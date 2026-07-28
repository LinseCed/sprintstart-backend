package com.sprintstart.sprintstartbackend.user.service

import com.sprintstart.sprintstartbackend.user.external.ProjectMember
import com.sprintstart.sprintstartbackend.user.external.ProjectMembershipApi
import com.sprintstart.sprintstartbackend.user.model.entity.ProjectUserAssignment
import com.sprintstart.sprintstartbackend.user.repository.ProjectRoleRepository
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
    private val projectRoleRepository: ProjectRoleRepository,
) : ProjectMembershipApi {
    @Transactional(readOnly = true)
    override fun onboardingTrackKeysInUse(): Set<String> {
        return projectRoleRepository.findAll().mapNotNull { it.onboardingTrackKey }.toSet()
    }

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
     * Precedence between the two role mechanisms is not decided here — it is
     * [ProjectUserAssignment.effectiveProjectRoles], defined once so no two surfaces can answer it
     * differently. Worth knowing while reading this: the assignment's own set has no writer, so in
     * practice these are the user's roles. The earlier note here claimed this read the roles held
     * *on this project* first "since that is the right grain"; it is the right grain, but it is not
     * a grain the data has ever had.
     *
     * Disagreement resolves to null, not to a winner. Somebody holding two roles with different
     * tracks is a real situation (a PM who also ships code), and picking one arbitrarily would put
     * the wrong vocabulary in front of them. Null lets onboarding fall back to its default, which
     * is the same answer they got before tracks existed.
     */
    private fun trackKeyFor(assignment: ProjectUserAssignment): String? =
        assignment.effectiveProjectRoles
            .mapNotNull { it.onboardingTrackKey }
            .distinct()
            .singleOrNull()
}
