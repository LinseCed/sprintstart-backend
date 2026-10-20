package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.model.response.board.MemoryRecapContent
import com.sprintstart.sprintstartbackend.onboarding.repository.BuddySessionRepository
import com.sprintstart.sprintstartbackend.shared.ids.UserId
import org.springframework.stereotype.Component

/**
 * The board's memory recap, read from the hire's latest buddy session and never written.
 *
 * Not [BuddyService.getOrCreateSession]: hydrating a card must not create a session.
 */
@Component
class BuddyMemoryRecapSource(
    private val buddySessionRepository: BuddySessionRepository,
) : MemoryRecapSource {
    override fun latestRecap(hire: UserId): MemoryRecapContent {
        val session = buddySessionRepository
            .findByUserIdOrderByCreatedAtDesc(hire.value)
            .firstOrNull()

        return MemoryRecapContent(
            memory = session?.summary,
            messagesRemembered = session?.summarizedCount ?: 0,
        )
    }
}
