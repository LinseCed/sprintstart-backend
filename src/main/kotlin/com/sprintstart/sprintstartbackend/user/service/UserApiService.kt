package com.sprintstart.sprintstartbackend.user.service

import com.sprintstart.sprintstartbackend.user.external.UserApi
import com.sprintstart.sprintstartbackend.user.repository.UserRepository
import java.util.UUID

/**
 * Service implementation of the user API used by other modules.
 *
 * Provides a small, module-facing interface for checking user-related information
 * without exposing internal user module implementation details.
 *
 * @property userRepository Repository used to access persisted user data.
 */
class UserApiService(
    private val userRepository: UserRepository,
) : UserApi {
    /**
     * Checks whether a user with the given identifier exists.
     *
     * @param id The unique identifier of the user to check.
     * @return `true` if a user with the given identifier exists, otherwise `false`.
     */
    override fun exists(id: UUID): Boolean {
        return userRepository.existsById(id)
    }
}
