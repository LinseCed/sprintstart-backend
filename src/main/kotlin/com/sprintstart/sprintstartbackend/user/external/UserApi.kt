package com.sprintstart.sprintstartbackend.user.external

import java.util.Optional
import java.util.UUID

interface UserApi {
    fun exists(id: UUID): Boolean

    fun getUserIdByAuthId(authId: String): Optional<UUID>

    fun searchUsers(
        search: String?,
        roleIds: List<UUID>?,
        projectIds: List<UUID>?,
        pageable: org.springframework.data.domain.Pageable,
    ): org.springframework.data.domain.Page<com.sprintstart.sprintstartbackend.user.external.dto.UserDto>

    fun getUsersByIds(ids: List<UUID>): List<com.sprintstart.sprintstartbackend.user.external.dto.UserDto>
}
