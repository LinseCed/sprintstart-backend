package com.sprintstart.sprintstartbackend.user.model.response.project

import com.sprintstart.sprintstartbackend.user.external.enums.Role
import com.sprintstart.sprintstartbackend.user.model.response.user.ProjectRoleSummary
import java.util.UUID

data class AdminProjectListResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val sources: List<ProjectSourceResponse>,
    val users: List<ProjectUserSummaryResponse>,
)

data class AdminProjectDetailResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val sources: List<ProjectSourceResponse>,
    val users: List<ProjectUserResponse>,
)

data class ProjectSourceResponse(
    val id: String,
    val name: String,
    val type: String,
    val status: String,
)

data class ProjectUserSummaryResponse(
    val id: UUID,
    val username: String,
    val email: String?,
)

data class ProjectUserResponse(
    val id: UUID,
    val username: String,
    val email: String?,
    val firstName: String,
    val lastName: String,
    val roles: Set<Role>,
    /**
     * The roles this person holds **on this project**, with ids.
     *
     * Ids rather than bare names because this list is now editable from the project surface, and
     * removing a role by name would take the wrong one off whenever two roles share a name.
     */
    val projectRoles: List<ProjectRoleSummary>,
    val enabled: Boolean,
)

data class DeleteProjectResponse(
    val id: UUID,
    val deleted: Boolean = true,
)
