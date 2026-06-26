package com.sprintstart.sprintstartbackend.user.controller

import com.sprintstart.sprintstartbackend.user.model.dto.DeleteUserResponse
import com.sprintstart.sprintstartbackend.user.model.dto.GetUserResponse
import com.sprintstart.sprintstartbackend.user.model.dto.PatchMeRequest
import com.sprintstart.sprintstartbackend.user.model.dto.PatchUserRequest
import com.sprintstart.sprintstartbackend.user.model.dto.UpdateUserEnabledRequest
import com.sprintstart.sprintstartbackend.user.service.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Tag(
    name = "Current User",
    description = "Self-service endpoints for the currently authenticated user.",
)
@RestController
@RequestMapping("/api/v1/users")
class UserSelfController(
    private val userService: UserService,
) {
    @Operation(
        summary = "Get current user",
        description = "Returns the combined SprintStart and Keycloak projection for the authenticated user.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "User found"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role to access own profile"),
            ApiResponse(responseCode = "404", description = "User not found"),
        ],
    )
    @GetMapping("/me")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('USER')")
    fun getMe(
        @Parameter(hidden = true)
        @AuthenticationPrincipal jwt: Jwt,
    ): GetUserResponse = userService.getMe(jwt.subject)

    @Operation(
        summary = "Patch current user",
        description = "Updates editable current-user profile fields. Passwords are handled by Keycloak directly.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "User patched successfully"),
            ApiResponse(responseCode = "400", description = "Invalid request body"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "403", description = "Insufficient role to update own profile"),
            ApiResponse(responseCode = "404", description = "User not found"),
        ],
    )
    @PatchMapping("/me")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('USER')")
    fun patchMe(
        @Valid @RequestBody request: PatchMeRequest,
        @Parameter(hidden = true)
        @AuthenticationPrincipal jwt: Jwt,
    ): GetUserResponse = userService.patchMe(jwt.subject, request)
}

@Tag(
    name = "Admin Users",
    description = "Administrative endpoints for managing users through SprintStart and Keycloak orchestration.",
)
@RestController
@RequestMapping("/api/v1/admin/users")
class AdminUserController(
    private val userService: UserService,
) {
    @Operation(
        summary = "Get all users",
        description = "Returns all user profiles visible for administration.",
    )
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    fun getAllUsers(): List<GetUserResponse> = userService.getAllUsers()

    @Operation(
        summary = "Get user by id",
        description = "Returns a single user profile by SprintStart user UUID.",
    )
    @GetMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    fun getUserById(
        @Parameter(description = "UUID of the user to retrieve")
        @PathVariable id: UUID,
    ): GetUserResponse = userService.getUserById(id)

    @Operation(
        summary = "Patch user base fields",
        description = "Updates editable base fields, working area and permission group. " +
            "Enabled state has a dedicated endpoint.",
    )
    @PatchMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    fun patchUserById(
        @Parameter(description = "UUID of the user to patch")
        @PathVariable id: UUID,
        @Valid @RequestBody request: PatchUserRequest,
    ): GetUserResponse = userService.patchAdminUserById(id, request)

    @Operation(
        summary = "Patch user enabled status",
        description = "Enables or disables the Keycloak account through the backend orchestration layer.",
    )
    @PatchMapping("/{id}/enabled")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    fun updateUserEnabledById(
        @Parameter(description = "UUID of the user whose account status should be updated")
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateUserEnabledRequest,
    ): GetUserResponse = userService.updateUserEnabledById(id, request)

    @Operation(
        summary = "Delete user by id",
        description = "Permanently deletes the user account in Keycloak and removes the local projection.",
    )
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    fun deleteUserById(
        @Parameter(description = "UUID of the user to delete")
        @PathVariable id: UUID,
    ): DeleteUserResponse = userService.deleteAdminUserById(id)
}
