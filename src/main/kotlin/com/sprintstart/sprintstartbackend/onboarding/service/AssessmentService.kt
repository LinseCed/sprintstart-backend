package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencySource
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ModuleStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.SkillAssessmentSessionStatus
import com.sprintstart.sprintstartbackend.onboarding.external.model.AssessmentHistoryEntrySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.AssessmentTargetsSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.AssessmentTurnRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.AssessmentTurnResponse
import com.sprintstart.sprintstartbackend.onboarding.external.model.CandidateCompetencySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.CandidateSignalSchema
import com.sprintstart.sprintstartbackend.onboarding.model.entity.SkillAssessmentSession
import com.sprintstart.sprintstartbackend.onboarding.model.entity.SkillAssessmentTurn
import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserCompetencyState
import com.sprintstart.sprintstartbackend.onboarding.model.exceptions.OnboardingAiException
import com.sprintstart.sprintstartbackend.onboarding.model.response.assessment.AnswerAssessmentResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.assessment.StartAssessmentResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyModuleRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.SkillAssessmentSessionRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserCompetencyStateRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
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
 * Per-project: a hire runs this interview once per project they are on, not once ever. Candidate
 * competencies are the [CompetencyKind.SKILL] nodes that project actually teaches -- the keys of
 * its live [com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyModule]s, the same
 * association the project's path already points modules through -- not the whole global catalog, so
 * a hire on the frontend project is never interviewed on a backend-only project's competencies. The
 * placement it writes still lands on the global ledger: "earn once, transfers across projects" is
 * unchanged, only the interview itself is scoped. `repo_signal` stays the empty placeholder, since
 * nothing in `ingestion` aggregates languages/frameworks yet.
 */
@Suppress("TooManyFunctions")
@Service
class AssessmentService(
    private val onboardingAiClient: OnboardingAiClient,
    private val skillAssessmentSessionRepository: SkillAssessmentSessionRepository,
    private val competencyRepository: CompetencyRepository,
    private val competencyModuleRepository: CompetencyModuleRepository,
    private val userCompetencyStateRepository: UserCompetencyStateRepository,
    private val userApi: UserApi,
    private val githubHistoryPriorService: GithubHistoryPriorService,
    transactionManager: PlatformTransactionManager,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // Mirrors BlueprintService: the AI call is a long-running suspend operation and must not run
    // inside a transaction, so DB reads/writes bracket it in their own explicit transactions.
    private val txTemplate = TransactionTemplate(transactionManager)
    private val readTxTemplate =
        TransactionTemplate(transactionManager).apply { isReadOnly = true }

    /**
     * Whether the authenticated user has ever completed an assessment session for this project.
     *
     * The frontend's "needs assessment" gate checks this -- a COMPLETED session is the thing the
     * assessment flow actually produces. Scoped per project: completing it for one project says
     * nothing about another, since the questions asked (and the project's live modules they were
     * scoped to) differ.
     *
     * @param authId The authenticated user's auth (JWT subject) id.
     * @param projectId The project to check completion for.
     * @throws ResponseStatusException 404 if no user exists for [authId].
     */
    fun hasCompletedAssessment(authId: String, projectId: UUID): Boolean {
        val userId = resolveUserId(authId)
        return skillAssessmentSessionRepository.existsByUserIdAndProjectIdAndStatus(
            userId,
            projectId,
            SkillAssessmentSessionStatus.COMPLETED,
        )
    }

    /**
     * Starts a new assessment for the authenticated user on [projectId], or resumes their
     * in-progress one for it.
     *
     * @param authId The authenticated user's auth (JWT subject) id.
     * @param projectId The project to run the interview for.
     * @return The session id and the question to show next, or `done=true` with no question if the
     * project has nothing configured to assess yet.
     */
    suspend fun startAssessment(authId: String, projectId: UUID): StartAssessmentResponse {
        val userId = resolveUserId(authId)

        // Reserve the session *before* the slow AI call. Resuming used to be checked against state
        // that only existed after it, so a second start issued while the first was still generating
        // saw nothing to resume and created its own -- four sessions for one assessment, three of
        // them stranded IN_PROGRESS with a question nobody ever saw.
        val reserved = withContext(Dispatchers.IO) {
            txTemplate.execute { reserveSession(userId, projectId) }!!
        }
        reserved.openQuestion?.let {
            return StartAssessmentResponse(sessionId = reserved.sessionId, question = it)
        }

        val candidates = withContext(Dispatchers.IO) {
            readTxTemplate.execute { loadCandidateCompetencies(projectId) }.orEmpty()
        }
        if (candidates.isEmpty()) {
            // Nothing this project teaches yet to place the hire against -- an honest empty
            // result, the same way GET /me/path treats "nothing set up yet" as a real state
            // rather than an error. Finishing without ever calling the AI also avoids handing it
            // an empty candidate list, which it cannot legitimately finish over either.
            return withContext(Dispatchers.IO) {
                txTemplate.execute { completeWithNothingToAssess(reserved.sessionId) }!!
            }
        }
        val candidateSignal = withContext(Dispatchers.IO) { loadCandidateSignal(userId) }
        val aiResponse = runAssessTurn(
            AssessmentTurnRequest(
                candidateCompetencies = candidates,
                candidateSignal = candidateSignal,
                turn = 0,
                maxTurns = MAX_TURNS,
                mustFinish = false,
            ),
        )
        val question = aiResponse.question ?: throw badGateway("start")

        return withContext(Dispatchers.IO) {
            txTemplate.execute {
                openFirstTurn(reserved.sessionId, question, aiResponse.targets.orEmpty())
            }!!
        }
    }

    private data class ReservedSession(
        val sessionId: UUID,
        val openQuestion: String?,
    )

    /**
     * Returns the user's single in-progress session for [projectId], creating an empty one if they
     * have none.
     *
     * Runs in its own short write transaction so it is the serialization point for concurrent
     * starts: whoever gets there first creates the row, everyone else finds it. A session reserved
     * this way has no turn yet -- [openFirstTurn] fills that in once the interviewer answers.
     */
    private fun reserveSession(userId: UUID, projectId: UUID): ReservedSession {
        val existing = skillAssessmentSessionRepository.findFirstByUserIdAndProjectIdAndStatusOrderByCreatedAtDesc(
            userId,
            projectId,
            SkillAssessmentSessionStatus.IN_PROGRESS,
        )
        if (existing != null) {
            return ReservedSession(existing.id, existing.turns.lastOrNull { it.answer == null }?.question)
        }

        val session = skillAssessmentSessionRepository.save(
            SkillAssessmentSession(userId = userId, projectId = projectId),
        )
        return ReservedSession(session.id, null)
    }

    /**
     * Finishes a reserved session immediately with no question and nothing assessed, because its
     * project has no live competency module to interview against yet.
     */
    private fun completeWithNothingToAssess(sessionId: UUID): StartAssessmentResponse {
        val session = skillAssessmentSessionRepository.findByIdOrNull(sessionId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Assessment session no longer exists")
        session.status = SkillAssessmentSessionStatus.COMPLETED
        session.updatedAt = Instant.now()
        return StartAssessmentResponse(sessionId = session.id, question = null, done = true)
    }

    /**
     * Writes the first question onto a reserved session, unless a racing start already did.
     *
     * Two concurrent starts can both reach the interviewer; only one question is kept, so both
     * callers see the same interview rather than one silently overwriting the other's turn.
     */
    private fun openFirstTurn(
        sessionId: UUID,
        question: String,
        targets: List<String>,
    ): StartAssessmentResponse {
        val session = skillAssessmentSessionRepository.findByIdOrNull(sessionId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Assessment session no longer exists")

        session.turns.lastOrNull { it.answer == null }?.let {
            return StartAssessmentResponse(sessionId = session.id, question = it.question)
        }

        session.turns.add(
            SkillAssessmentTurn(
                session = session,
                turnIndex = session.turns.size,
                question = question,
                targets = targets.toMutableList(),
            ),
        )
        session.updatedAt = Instant.now()
        return StartAssessmentResponse(sessionId = session.id, question = question)
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

        val turnState = withContext(Dispatchers.IO) {
            readTxTemplate.execute {
                val session = loadOwnedSession(userId, sessionId)
                val openTurn = requireOpenTurn(session, sessionId)
                TurnState(
                    history = buildHistory(session, openTurn, answer),
                    targets = buildTargets(session),
                    nextTurnIndex = openTurn.turnIndex + 1,
                    candidates = loadCandidateCompetencies(session.projectId),
                )
            }!!
        }

        val mustFinish = turnState.nextTurnIndex >= MAX_TURNS - 1
        val aiResponse = runAssessTurn(
            AssessmentTurnRequest(
                candidateCompetencies = turnState.candidates,
                candidateSignal = withContext(Dispatchers.IO) { loadCandidateSignal(userId) },
                history = turnState.history,
                targets = turnState.targets,
                turn = turnState.nextTurnIndex,
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
                    val validKeys = turnState.candidates.map { it.key }.toSet()
                    for (result in aiResponse.assessments.orEmpty()) {
                        if (result.key !in validKeys) continue
                        writeCompetencyState(userId, result.key, result.level, result.confidence)
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
                            targets = aiResponse.targets.orEmpty().toMutableList(),
                        ),
                    )
                    AnswerAssessmentResponse(done = false, question = question)
                }
            }!!
        }
    }

    private data class TurnState(
        val history: List<AssessmentHistoryEntrySchema>,
        val targets: List<AssessmentTargetsSchema>,
        val nextTurnIndex: Int,
        val candidates: List<CandidateCompetencySchema>,
    )

    /**
     * What every past question set out to probe, per turn.
     *
     * Turns that targeted nothing are dropped rather than sent as empty lists: an interview
     * started before targets were recorded would otherwise look like it had probed a set of keys
     * and come up empty, when in fact nothing was ever asked. Absent is the honest reading, and it
     * makes the interviewer cover more rather than less.
     */
    private fun buildTargets(session: SkillAssessmentSession): List<AssessmentTargetsSchema> =
        session.turns
            .filter { it.targets.isNotEmpty() }
            .map { AssessmentTargetsSchema(turn = it.turnIndex, keys = it.targets.toList()) }

    /**
     * The candidate's consented involvement prior, or an empty signal.
     *
     * Sent on every turn because the AI service is stateless and re-derives its belief from the
     * request each time -- omitting it after turn 0 would silently change how later turns are
     * calibrated. Consent is re-checked on each read, so withdrawing it takes effect immediately,
     * mid-interview included.
     */
    private fun loadCandidateSignal(userId: UUID): CandidateSignalSchema {
        val prior = githubHistoryPriorService.getPrior(userId) ?: return CandidateSignalSchema()
        return CandidateSignalSchema(signals = prior.signals.toMap())
    }

    /**
     * The [CompetencyKind.SKILL] competencies [projectId] actually teaches: the keys of its live
     * [com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyModule]s, the same
     * per-`(competencyKey, projectId)` association the path already points modules through. A
     * project with no live modules yet returns an empty list -- callers must treat that as nothing
     * to assess, not as a request to send an empty candidate set to the AI.
     */
    private fun loadCandidateCompetencies(projectId: UUID): List<CandidateCompetencySchema> {
        val taughtKeys = competencyModuleRepository
            .findAllByProjectIdAndStatus(projectId, ModuleStatus.ACTIVE)
            .map { it.competencyKey }
            .toSet()
        if (taughtKeys.isEmpty()) return emptyList()

        return competencyRepository
            .findAllByKeyIn(taughtKeys)
            .filter { it.kind == CompetencyKind.SKILL }
            .map { CandidateCompetencySchema(key = it.key, label = it.label, description = it.description.orEmpty()) }
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
    private fun writeCompetencyState(userId: UUID, competencyKey: String, level: String, confidence: Double) {
        val rank = placementRank(level, confidence)
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

    /**
     * The rank a placement is allowed to record.
     *
     * A level the interviewer is not confident about is recorded as `0` -- "we asked, and saw no
     * competence" -- rather than as the level it guessed. The interviewer is explicitly instructed
     * to answer `beginner` with low confidence when a candidate says "I don't know", so without
     * this floor a hire who told us they know nothing is credited with the competency.
     *
     * `0` is a real state elsewhere in the ledger (known-but-unplaced, filtered out of matching),
     * so this records that the assessment happened without claiming a skill.
     */
    private fun placementRank(level: String, confidence: Double): Int {
        if (confidence < MIN_PLACEMENT_CONFIDENCE) {
            return 0
        }
        return LEVEL_RANKS[level.trim().lowercase()] ?: 0
    }

    /**
     * Runs one AI interviewer turn, translating a transport-level AI failure into a retryable
     * 503 instead of letting [OnboardingAiException] surface as an opaque 500.
     *
     * The AI service itself now refuses to fabricate a placement when it is still too early to
     * legitimately finish (a model that won't stop trying to finish early, or an unparseable
     * response at that point) -- it answers with its own 503 rather than a hollow `done=true`.
     * This is that failure reaching the caller as what it is: a "please retry", not a finished
     * assessment nothing backed.
     */
    private suspend fun runAssessTurn(request: AssessmentTurnRequest): AssessmentTurnResponse =
        try {
            onboardingAiClient.assessTurn(request)
        } catch (@Suppress("SwallowedException") e: OnboardingAiException) {
            logger.warn("Assessment turn unavailable: {}", e.message)
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Assessment is temporarily unavailable, please try again",
            )
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

        // Below this, a placement records 0 rather than the level it guessed. The interviewer uses
        // low confidence precisely to mean "no evidence either way".
        const val MIN_PLACEMENT_CONFIDENCE = 0.4

        // Aligned 1:1 with the AI SKILL_LEVELS (beginner..expert -> 1..4); unknown -> 0.
        val LEVEL_RANKS = mapOf(
            "beginner" to 1,
            "intermediate" to 2,
            "advanced" to 3,
            "expert" to 4,
        )
    }
}
