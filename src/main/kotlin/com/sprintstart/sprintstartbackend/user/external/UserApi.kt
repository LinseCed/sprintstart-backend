package com.sprintstart.sprintstartbackend.user.external

import com.sprintstart.sprintstartbackend.user.external.dto.ProjectRoleDto
import com.sprintstart.sprintstartbackend.user.external.dto.UserDto
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.util.Optional
import java.util.UUID

/**
 * Exported user-module API for other backend modules.
 *
 * Other modules should depend on this interface instead of calling user-module services
 * or repositories directly.
 */
interface UserApi {
    /**
     * Checks whether a user projection exists for the given SprintStart user ID.
     *
     * @param id Internal SprintStart user identifier.
     * @return `true` when the user exists, otherwise `false`.
     */
    fun exists(id: UUID): Boolean

    /**
     * Resolves the internal SprintStart user ID for a Keycloak authentication subject.
     *
     * @param authId External authentication identifier from Keycloak.
     * @return The matching user ID when present.
     */
    fun getUserIdByAuthId(authId: String): Optional<UUID>

    fun getUserByAuthId(authId: String): UserDto

    fun searchUsers(
        search: String?,
        roleIds: List<UUID>?,
        projectIds: List<UUID>?,
        pageable: Pageable,
    ): Page<UserDto>

    fun getUsersByIds(ids: List<UUID>): List<UserDto>

    /**
     * Returns the onboarding-relevant profile for a user identified by auth ID.
     *
     * @param authId External authentication identifier.
     * @return The user's onboarding profile when present.
     */
    fun getOnboardingProfileByAuthId(authId: String): Optional<UserOnboardingProfile>

    fun userHasAccessToProject(authId: String, projectId: UUID): Boolean

    /**
     * Returns the roles a user holds **within a specific project**.
     *
     * Resolved from the user's project assignment (`ProjectUserAssignment`), so — unlike the
     * project-agnostic role set on [UserOnboardingProfile] — this answers "what is
     * this user in *this* project", which per-project onboarding needs to pick the blueprint scope.
     * Empty when the user has no assignment or no roles in that project.
     *
     * @param userId Internal SprintStart user identifier.
     * @param projectId The project to resolve roles within.
     * @return The user's roles in that project; empty if none.
     */
    fun getProjectRolesForUser(userId: UUID, projectId: UUID): List<ProjectRoleDto>
}
