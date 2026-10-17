package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.shared.ids.BuddySessionId
import com.sprintstart.sprintstartbackend.shared.ids.ProjectId
import com.sprintstart.sprintstartbackend.shared.ids.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component

/**
 * Folds a conversation's backlog into the buddy's memory without anybody waiting on it.
 *
 * Fire-and-forget on the application scope, matching `CorpusIndexedListener`: the fold is a prompt-shaping
 * device, so one that dies costs a longer prompt on the next turn and nothing else. It must never be awaited
 * by a reply or an opening, and [BuddyCompactionService] never throws.
 */
@Component
class BuddyCompactionScheduler(
    private val compaction: BuddyCompactionService,
    private val applicationScope: CoroutineScope,
) {
    /** Folds a hire's conversation once the turn that grew it is stored. */
    fun foldHireConversation(user: UserId, session: BuddySessionId) {
        applicationScope.launch { compaction.compactIfNeeded(user.value, session.value) }
    }

    /** Folds a manager's team conversation about a project. */
    fun foldTeamConversation(user: UserId, project: ProjectId) {
        applicationScope.launch { compaction.compactTeamIfNeeded(user.value, project.value) }
    }
}
