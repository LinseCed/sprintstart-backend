package com.sprintstart.sprintstartbackend.user.controller

import com.sprintstart.sprintstartbackend.user.model.entity.ProjectRole
import com.sprintstart.sprintstartbackend.user.repository.ProjectRoleRepository
import com.sprintstart.sprintstartbackend.user.repository.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CreateProjectRoleRequest(
    val name: String,
    val description: String,
)

data class AssignProjectRoleRequest(
    val roleId: UUID,
)

@RestController
@RequestMapping("/api/v1")
class ProjectRoleController(
    private val projectRoleRepository: ProjectRoleRepository,
    private val userRepository: UserRepository,
) {
    @GetMapping("/projectRoles")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    fun getAllRoles(): List<ProjectRole> {
        return projectRoleRepository.findAll()
    }

    @PostMapping("/projectRoles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    fun createRole(@RequestBody request: CreateProjectRoleRequest): ProjectRole {
        val role = ProjectRole(
            name = request.name,
            description = request.description,
        )
        return projectRoleRepository.save(role)
    }

    @DeleteMapping("/projectRoles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    fun deleteRole(@PathVariable roleId: UUID) {
        projectRoleRepository.deleteById(roleId)
    }

    @PostMapping("/users/{userId}/project-roles")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    fun assignRoleToUser(
        @PathVariable userId: UUID,
        @RequestBody request: AssignProjectRoleRequest,
    ) {
        val user = userRepository.findById(userId).orElseThrow()
        val role = projectRoleRepository.findById(request.roleId).orElseThrow()

        user.projectRoles.add(role)
        userRepository.save(user)
    }

    @DeleteMapping("/users/{userId}/project-roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    fun unassignRoleFromUser(
        @PathVariable userId: UUID,
        @PathVariable roleId: UUID,
    ) {
        val user = userRepository.findById(userId).orElseThrow()
        user.projectRoles.removeIf { it.id == roleId }
        userRepository.save(user)
    }
}
