package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencySource
import com.sprintstart.sprintstartbackend.onboarding.external.enums.SkillAssessmentSessionStatus
import com.sprintstart.sprintstartbackend.onboarding.external.model.AssessmentHistoryEntrySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.AssessmentTurnRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.CandidateCompetencySchema
import com.sprintstart.sprintstartbackend.onboarding.model.entity.SkillAssessmentSession
import com.sprintstart.sprintstartbackend.onboarding.model.entity.SkillAssessmentTurn
import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserCompetencyState
import com.sprintstart.sprintstartbackend.onboarding.model.response.assessment.AnswerAssessmentResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.assessment.StartAssessmentResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.SkillAssessmentSessionRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserCompetencyStateRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * The turn-based adaptive skill-assessment session: starts/resumes the interview, forwards each
 * turn to the AI interviewer (Seam 1), and writes the final placement to the durable ledger
 * ([UserCompetencyState]).
 *
 * Candidate competencies are today's [CompetencyKind.SKILL] nodes with a flat `role_weight = 1.0`
 * — weighting by blueprint requirement or graph centrality needs Phase 2's traversal and a
 * blueprint-step-to-competency-key bridge that doesn't exist yet. `repo_signal` is always the
 * empty placeholder for the same reason: nothing in `ingestion` aggregates languages/frameworks
 * today. Both are flagged follow-ups, not oversights.
 */
@Suppress("TooManyFunctions")
@Service
class AssessmentService(
    private val onboardingAiClient: OnboardingAiClient,
    private val skillAssessmentSessionRepository: SkillAssessmentSessionRepository,
    private val competencyRepository: CompetencyRepository,
    private val userCompetencyStateRepository: UserCompetencyStateRepository,
    private val userApi: UserApi,
    transactionManager: PlatformTransactionManager,
) {
    // Mirrors BlueprintService: the AI call is a long-running suspend operation and must not run
    // inside a transaction, so DB reads/writes bracket it in their own explicit transactions.
    private val txTemplate = TransactionTemplate(transactionManager)
    private val readTxTemplate =
        TransactionTemplate(transactionManager).apply { isReadOnly = true }

    /**
     * Whether the authenticated user has ever completed an assessment session.
     *
     * The frontend's "needs assessment" gate checks this instead of the retired self-reported
     * skill-wizard data -- a COMPLETED session is the thing the assessment flow actually produces.
     *
     * @param authId The authenticated user's auth (JWT subject) id.
     * @throws ResponseStatusException 404 if no user exists for [authId].
     */
    fun hasCompletedAssessment(authId: String): Boolean {
        val userId = resolveUserId(authId)
        return skillAssessmentSessionRepository.existsByUserIdAndStatus(
            userId,
            SkillAssessmentSessionStatus.COMPLETED,
        )
    }

    /**
     * Starts a new assessment for the authenticated user, or resumes their in-progress one.
     *
     * @param authId The authenticated user's auth (JWT subject) id.
     * @return The session id and the question to show next.
     */
    suspend fun startAssessment(authId: String): StartAssessmentResponse {
        val userId = resolveUserId(authId)

        withContext(Dispatchers.IO) {
            readTxTemplate.execute { findResumableSession(userId) }
        }?.let { return it }

        val candidates = withContext(Dispatchers.IO) {
            readTxTemplate.execute { loadCandidateCompetencies() }.orEmpty()
        }
        val aiResponse = onboardingAiClient.assessTurn(
            AssessmentTurnRequest(
                candidateCompetencies = candidates,
                turn = 0,
                maxTurns = MAX_TURNS,
                mustFinish = false,
            ),
        )
        val question = aiResponse.question ?: throw badGateway("start")

        return withContext(Dispatchers.IO) {
            txTemplate.execute {
                val session = SkillAssessmentSession(userId = userId)
                session.turns.add(
                    SkillAssessmentTurn(session = session, turnIndex = 0, question = question),
                )
                skillAssessmentSessionRepository.save(session)
                StartAssessmentResponse(sessionId = session.id, question = question)
            }!!
        }
    }

    /**
     * Submits the candidate's answer for the currently open turn and advances the interview.
     *
     * @param authId The authenticated user's auth (JWT subject) id.
     * @param sessionId The session being answered.
     * @param answer The candidate's free-text answer.
     * @return The next question, or `done=true` once the AI has returned a final placement.
     * @throws ResponseStatusException 404 if no session with this id belongs to the user; 409 if
     * the session has no open turn to answer.
     */
    suspend fun answerAssessment(
        authId: String,
        sessionId: UUID,
        answer: String,
    ): AnswerAssessmentResponse {
        val userId = resolveUserId(authId)

        val (history, nextTurnIndex, candidates) = withContext(Dispatchers.IO) {
            readTxTemplate.execute {
                val session = loadOwnedSession(userId, sessionId)
                val openTurn = requireOpenTurn(session, sessionId)
                val history = buildHistory(session, openTurn, answer)
                Triple(history, openTurn.turnIndex + 1, loadCandidateCompetencies())
            }!!
        }

        val mustFinish = nextTurnIndex >= MAX_TURNS - 1
        val aiResponse = onboardingAiClient.assessTurn(
            AssessmentTurnRequest(
                candidateCompetencies = candidates,
                history = history,
                turn = nextTurnIndex,
                maxTurns = MAX_TURNS,
                mustFinish = mustFinish,
            ),
        )

        return withContext(Dispatchers.IO) {
            txTemplate.execute {
                val session = loadOwnedSession(userId, sessionId)
                val openTurn = requireOpenTurn(session, sessionId)
                openTurn.answer = answer
                session.updatedAt = Instant.now()

                if (aiResponse.done) {
                    val validKeys = candidates.map { it.key }.toSet()
                    for (result in aiResponse.assessments.orEmpty()) {
                        if (result.key !in validKeys) continue
                        writeCompetencyState(userId, result.key, result.level)
                    }
                    session.status = SkillAssessmentSessionStatus.COMPLETED
                    AnswerAssessmentResponse(done = true, question = null)
                } else {
                    val question = aiResponse.question ?: throw badGateway("continue")
                    session.turns.add(
                        SkillAssessmentTurn(
                            session = session,
                            turnIndex = openTurn.turnIndex + 1,
                            question = question,
                        ),
                    )
                    AnswerAssessmentResponse(done = false, question = question)
                }
            }!!
        }
    }

    /** Returns the resumable question for an existing in-progress session, or `null`. */
    private fun findResumableSession(userId: UUID): StartAssessmentResponse? {
        val session = skillAssessmentSessionRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(
            userId,
            SkillAssessmentSessionStatus.IN_PROGRESS,
        ) ?: return null
        val openTurn = session.turns.lastOrNull { it.answer == null } ?: return null
        return StartAssessmentResponse(sessionId = session.id, question = openTurn.question)
    }

    private fun loadCandidateCompetencies(): List<CandidateCompetencySchema> =
        competencyRepository.findAllByKind(CompetencyKind.SKILL).map {
            CandidateCompetencySchema(key = it.key, label = it.label, description = it.description.orEmpty())
        }

    private fun loadOwnedSession(userId: UUID, sessionId: UUID): SkillAssessmentSession {
        val session = skillAssessmentSessionRepository
            .findById(sessionId)
            .orElseThrow { notFound(sessionId) }
        if (session.userId != userId) throw notFound(sessionId)
        return session
    }

    private fun requireOpenTurn(session: SkillAssessmentSession, sessionId: UUID): SkillAssessmentTurn =
        session.turns.lastOrNull { it.answer == null }
            ?: throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "No open turn to answer for session: $sessionId",
            )

    /** Flattens a session's turns into AI history, substituting [pendingAnswer] on [openTurn]. */
    private fun buildHistory(
        session: SkillAssessmentSession,
        openTurn: SkillAssessmentTurn,
        pendingAnswer: String,
    ): List<AssessmentHistoryEntrySchema> =
        session.turns.flatMap { turn ->
            val answer = if (turn.id == openTurn.id) pendingAnswer else turn.answer
            buildList {
                add(AssessmentHistoryEntrySchema(role = "assistant", content = turn.question))
                if (answer != null) add(AssessmentHistoryEntrySchema(role = "user", content = answer))
            }
        }

    /**
     * The ledger write is monotonic: a self-reported placement never overwrites a
     * [CompetencySource.VERIFIED] entry (proof always outranks a chat placement), and a
     * re-assessment never lowers an already-recorded level -- reconciliation's "never un-earns
     * progress" invariant applies to the ledger itself, not just graph changes.
     */
    private fun writeCompetencyState(userId: UUID, competencyKey: String, level: String) {
        val rank = LEVEL_RANKS[level.trim().lowercase()] ?: 0
        val existing = userCompetencyStateRepository.findByUserIdAndCompetencyKey(userId, competencyKey)
        if (existing != null) {
            if (existing.source == CompetencySource.VERIFIED) return
            existing.level = maxOf(existing.level, rank)
            existing.source = CompetencySource.ASSESSED
            existing.updatedAt = Instant.now()
        } else {
            userCompetencyStateRepository.save(
                UserCompetencyState(
                    userId = userId,
                    competencyKey = competencyKey,
                    level = rank,
                    source = CompetencySource.ASSESSED,
                ),
            )
        }
    }

    private fun resolveUserId(authId: String): UUID =
        userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: $authId") }

    private fun notFound(sessionId: UUID) =
        ResponseStatusException(HttpStatus.NOT_FOUND, "No assessment session found with id: $sessionId")

    private fun badGateway(phase: String) =
        ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI service returned no question to $phase the assessment")

    private companion object {
        const val MAX_TURNS = 6

        // Aligned 1:1 with the AI SKILL_LEVELS (beginner..expert -> 1..4); unknown -> 0.
        val LEVEL_RANKS = mapOf(
            "beginner" to 1,
            "intermediate" to 2,
            "advanced" to 3,
            "expert" to 4,
        )
    }
}
