package com.sprintstart.sprintstartbackend.onboarding.repository

import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserGraphPin
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserGraphPinRepository : JpaRepository<UserGraphPin, UUID> {
    fun findByUserId(userId: UUID): UserGraphPin?
}
