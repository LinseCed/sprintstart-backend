package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyAgentMessageDto
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyCompactRequest
import com.sprintstart.sprintstartbackend.onboarding.model.exceptions.OnboardingAiException
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toAgentMessage
import com.sprintstart.sprintstartbackend.onboarding.repository.BuddyMessageRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.BuddySessionRepository
import org.slf4j.LoggerFactory
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Folds a buddy conversation's oldest turns into the mentor's durable memory note.
 *
 * ### Why this is not part of a turn
 *
 * ⚠️ **The fold used to run in front of the answer the hire was waiting for.** It rode
 * `BuddyAgentRequest.summarizeUpto`, and the AI service performed it as the *first* step of the
 * agent turn — before the model began composing a reply. Because the cursor advanced by exactly
 * what it folded, the active window sat at [BuddyService.WINDOW] forever after it first filled, so
 * this was not an occasional cost: past roughly ten exchanges in a sitting, **every** turn paid an
 * extra serialized model call to compress one exchange.
 *
 * ⚠️ Its *quality* was never the problem, and an earlier note in this workspace claiming otherwise
 * was wrong. The fold has always had its own prompt and its own call at temperature 0. What it did
 * not have was a moment when nobody was waiting. That is all this service adds.
 *
 * ### Read, call, re-read
 *
 * The model call happens outside any transaction — the shape every AI-touching service here uses —
 * so the write must re-read what it enforces. Two things guard the swap, and they catch different
 * races:
 *
 * - **The cursor comparison** catches a fold that committed while the model was thinking.
 * - **[com.sprintstart.sprintstartbackend.onboarding.model.entity.BuddySession.version]** catches
 *   one committing *between* the re-read and the flush. ⚠️ A re-check alone is not a lock:
 *   `backend#170` is the local cautionary tale, where read-then-insert with no unique index started
 *   two assessment sessions at once and narrowing the window did not close it.
 *
 * Losing either race discards this fold and leaves the cursor alone. Nothing is lost by that: the
 * transcript is durable, and the next turn re-triggers the pass.
 */
@Service
class BuddyCompactionService(
    private val buddySessionRepository: BuddySessionRepository,
    private val buddyMessageRepository: BuddyMessageRepository,
    private val onboardingAiClient: OnboardingAiClient,
    transactionManager: PlatformTransactionManager,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val readTxTemplate = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val writeTxTemplate = TransactionTemplate(transactionManager)

    /**
     * Folds this session's backlog into its memory note, if it has one.
     *
     * Safe to call after every turn: a conversation whose active window still fits does nothing and
     * costs one query. **Never throws** — the caller is a fire-and-forget launch, and a fold that
     * fails must leave a working conversation behind, not a failed reply.
     *
     * @param userId The hire whose session to compact.
     */
    suspend fun compactIfNeeded(userId: UUID) {
        val plan = readTxTemplate.execute { planFor(userId) } ?: return

        val request = BuddyCompactRequest(priorSummary = plan.priorSummary, folded = plan.folded)
        val memory = try {
            onboardingAiClient.compactBuddyMemory(request).memory
        } catch (@Suppress("SwallowedException") e: OnboardingAiException) {
            // The note is a prompt-shaping device, never the record. An unavailable model costs a
            // longer prompt on the next turn and nothing else, so this is a warning and not a
            // retry queue.
            logger.warn("Buddy compaction skipped for user {}: {}", userId, e.message)
            return
        }

        applyFold(plan, memory)
    }

    private fun planFor(userId: UUID): FoldPlan? {
        val session = buddySessionRepository.findByUserId(userId) ?: return null
        val messages = buddyMessageRepository.findAllBySessionIdOrderByCreatedAtAsc(session.id)
        // Fold whatever it takes to bring the active window back to WINDOW — not a fixed slice.
        // A pass that missed its turn (the model was down, the process restarted) catches up in one
        // call rather than one exchange at a time.
        val foldCount = messages.size - session.summarizedCount - BuddyService.WINDOW
        if (foldCount <= 0) return null
        return FoldPlan(
            sessionId = session.id,
            cursor = session.summarizedCount,
            priorSummary = session.summary,
            folded = messages
                .drop(session.summarizedCount)
                .take(foldCount)
                .map { it.toAgentMessage() },
        )
    }

    private fun applyFold(plan: FoldPlan, memory: String) {
        try {
            writeTxTemplate.execute {
                val session = buddySessionRepository.findById(plan.sessionId).orElse(null)
                    ?: return@execute
                if (session.summarizedCount != plan.cursor) {
                    // A fold committed while the model was thinking. Discarding this one is
                    // correct: applying it would move the cursor past messages that the note the
                    // other pass wrote does not cover.
                    logger.debug(
                        "Discarding stale buddy fold for session {}: cursor moved {} -> {}",
                        plan.sessionId,
                        plan.cursor,
                        session.summarizedCount,
                    )
                    return@execute
                }
                session.summary = memory
                session.summarizedCount = plan.cursor + plan.folded.size
                buddySessionRepository.save(session)
            }
        } catch (@Suppress("SwallowedException") e: ObjectOptimisticLockingFailureException) {
            // The other half of the same race: a concurrent fold committed between the re-read and
            // the flush. Same outcome, and the next turn tries again.
            logger.debug("Buddy fold for session {} lost the swap: {}", plan.sessionId, e.message)
        }
    }

    /**
     * One fold, decided under a read transaction and applied under a write one.
     *
     * [cursor] is carried so the write can tell whether the session moved underneath it — the
     * value it must still see, not the value it will set.
     */
    private data class FoldPlan(
        val sessionId: UUID,
        val cursor: Int,
        val priorSummary: String?,
        val folded: List<BuddyAgentMessageDto>,
    )
}
