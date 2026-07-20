package com.sprintstart.sprintstartbackend.user.external

import com.sprintstart.sprintstartbackend.user.external.dto.ProjectRoleDto
import com.sprintstart.sprintstartbackend.user.external.dto.UserDto
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.Instant
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

    /**
     * Returns the GitHub account a user contributes as, if they have declared one.
     *
     * Artifact verification uses this to attribute a submitted pull request to the hire who
     * submitted it. Always lower-cased, because GitHub logins are case-insensitive and a case
     * difference must not read as a different person.
     *
     * @param userId Internal SprintStart user identifier.
     * @return The user's GitHub login, or `null` when they have none (or do not exist).
     */
    fun getGithubLoginByUserId(userId: UUID): String?

    /**
     * Everything the GitHub-history seeding feature needs about a user, in one read.
     *
     * Bundled rather than exposed as three accessors because they are only ever used together, and
     * the module boundary should describe a purpose rather than mirror columns.
     *
     * @return The user's seeding context, or `null` when no such user exists.
     */
    fun getGithubSeedingContext(userId: UUID): GithubSeedingContext?

    /**
     * Records or clears consent for using a user's existing repository work to calibrate their
     * skill assessment. `null` withdraws it.
     */
    fun setGithubSeedingConsent(userId: UUID, consentedAt: Instant?)
}

/**
 * The user-module facts a consent-gated history prior is built from.
 *
 * @property githubLogin The account their work is attributed to; `null` until declared.
 * @property projectIds The projects whose corpus may be read on their behalf -- never any other.
 * @property seedingConsentAt When they opted in, or `null` when they have not (or withdrew).
 */
data class GithubSeedingContext(
    val githubLogin: String?,
    val projectIds: Set<UUID>,
    val seedingConsentAt: Instant?,
)
