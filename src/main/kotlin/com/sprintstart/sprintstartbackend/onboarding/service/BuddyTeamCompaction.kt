package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.buddy.service.BuddyCompactionScheduler
import com.sprintstart.sprintstartbackend.buddy.service.BuddyCompactionService
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toAgentMessage
import com.sprintstart.sprintstartbackend.onboarding.repository.BuddyTeamMessageRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.BuddyTeamSessionRepository
import com.sprintstart.sprintstartbackend.shared.ids.ProjectId
import com.sprintstart.sprintstartbackend.shared.ids.UserId
import org.springframework.stereotype.Component

/**
 * Folds a manager's team conversation about one project by the buddy's compaction rules, from team mode's
 * own tables and never into the hire's note.
 */
@Component
class BuddyTeamCompaction(
    private val compaction: BuddyCompactionService,
    private val scheduler: BuddyCompactionScheduler,
    private val buddyTeamSessionRepository: BuddyTeamSessionRepository,
    private val buddyTeamMessageRepository: BuddyTeamMessageRepository,
) {
    /** Folds the conversation in the background once the turn that grew it is stored. */
    fun foldInBackground(user: UserId, project: ProjectId) {
        scheduler.inBackground { compactIfNeeded(user, project) }
    }

    /** A no-op while the window fits, and never throws, as [BuddyCompactionService.compact] promises. */
    suspend fun compactIfNeeded(user: UserId, project: ProjectId) {
        compaction.compact(
            BuddyCompactionService.Store(
                label = "user ${user.value} on project ${project.value}",
                find = { buddyTeamSessionRepository.findByUserIdAndProjectId(user.value, project.value) },
                findById = { buddyTeamSessionRepository.findById(it).orElse(null) },
                transcript = { sessionId ->
                    buddyTeamMessageRepository
                        .findAllBySessionIdOrderByCreatedAtAsc(sessionId)
                        .map { it.toAgentMessage() }
                },
                save = { buddyTeamSessionRepository.save(it) },
            ),
        )
    }
}
