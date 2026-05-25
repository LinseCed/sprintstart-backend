package com.sprintstart.sprintstartbackend.user.mapper

import com.sprintstart.sprintstartbackend.user.model.dto.CreateUserResponse
import com.sprintstart.sprintstartbackend.user.model.entity.User

fun User.toCreateResponse(): CreateUserResponse {
    return CreateUserResponse(
        id = this.id,
        username = this.username,
        firstname = this.firstname,
        lastname = this.lastname,
        primaryRole = this.primaryRole,
        secondaryRole = this.secondaryRole,
        workingArea = this.workingArea,
    )
}