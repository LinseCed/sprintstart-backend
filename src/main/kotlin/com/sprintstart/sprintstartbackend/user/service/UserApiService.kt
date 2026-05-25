package com.sprintstart.sprintstartbackend.user.service

import com.sprintstart.sprintstartbackend.user.external.UserApi
import com.sprintstart.sprintstartbackend.user.repository.UserRepository
import java.util.UUID

class UserApiService(
    private val userRepository: UserRepository,
) : UserApi {
    override fun exists(id: UUID): Boolean {
        return userRepository.existsById(id)
    }
}
