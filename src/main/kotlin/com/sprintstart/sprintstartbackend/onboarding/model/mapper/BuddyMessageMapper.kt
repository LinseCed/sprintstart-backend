package com.sprintstart.sprintstartbackend.onboarding.model.mapper

import com.sprintstart.sprintstartbackend.onboarding.model.entity.BuddyMessage
import com.sprintstart.sprintstartbackend.onboarding.model.response.buddy.BuddyMessageResponse

fun BuddyMessage.toResponse(): BuddyMessageResponse =
    BuddyMessageResponse(
        role = role,
        content = content,
        createdAt = createdAt,
    )
