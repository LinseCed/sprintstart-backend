package com.sprintstart.sprintstartbackend.user.service

import com.sprintstart.sprintstartbackend.user.mapper.toCreateResponse
import com.sprintstart.sprintstartbackend.user.model.dto.CreateUserRequest
import com.sprintstart.sprintstartbackend.user.model.dto.CreateUserResponse
import com.sprintstart.sprintstartbackend.user.model.entity.User
import com.sprintstart.sprintstartbackend.user.repository.UserRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service

@Service
class UserService (
    private val userRepository: UserRepository,
) {

    @Transactional
    fun createUser(request: CreateUserRequest): CreateUserResponse {
        val user: User = User(
            username = request.username,
            firstname = request.firstname,
            lastname = request.lastname,
            workingArea = request.workingArea
        )

        return userRepository.save(user).toCreateResponse()
    }
}