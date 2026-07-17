package com.sprintstart.sprintstartbackend.onboarding.repository

import com.sprintstart.sprintstartbackend.onboarding.model.entity.VerificationAttempt
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface VerificationAttemptRepository : JpaRepository<VerificationAttempt, UUID> {
    fun findAllByVerificationIdAndUserIdOrderByAttemptNoDesc(
        verificationId: UUID,
        userId: UUID,
    ): List<VerificationAttempt>

    fun countByVerificationIdAndUserId(verificationId: UUID, userId: UUID): Int
}
