package com.sprintstart.sprintstartbackend.user.external.dto

import java.util.UUID

data class UserDto(
    val id: UUID,
    val username: String,
    val firstname: String,
    val lastname: String,
    val avatarUrl: String?,
    val profileIcon: String?,
    val projects: Set<ProjectDto>,
    val projectRoles: List<ProjectRoleDto>,
)

data class ProjectDto(
    val projectId: UUID,
    val name: String,
    val description: String?,
)

data class ProjectRoleDto(
    val roleId: UUID,
    val name: String,
    val description: String,
)
