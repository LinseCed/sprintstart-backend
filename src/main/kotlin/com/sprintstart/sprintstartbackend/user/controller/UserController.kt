package com.sprintstart.sprintstartbackend.user.controller

import com.sprintstart.sprintstartbackend.user.model.dto.CreateUserRequest
import com.sprintstart.sprintstartbackend.user.model.dto.CreateUserResponse
import com.sprintstart.sprintstartbackend.user.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/users")
class UserController (
    private val userService: UserService
) {

    @PostMapping()
    @ResponseStatus(HttpStatus.CREATED)
    fun createUser(@RequestBody request : CreateUserRequest): CreateUserResponse {
        return userService.createUser(request)
    }

//    Todo: add Get All

//    Todo: add Get for one

//    Todo: add Put

//    Todo: add Patch for role

//    Todo: add Remove
}