package com.sprintstart.sprintstartbackend.onboarding.external

import com.sprintstart.sprintstartbackend.ApplicationConfig
import com.sprintstart.sprintstartbackend.onboarding.external.model.ActiveCompetencySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.ActiveEdgeSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.ArtifactEvidenceDto
import com.sprintstart.sprintstartbackend.onboarding.external.model.AssessmentTurnRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.AssessmentTurnResponse
import com.sprintstart.sprintstartbackend.onboarding.external.model.BlueprintSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.GenerateBlueprintsRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.GenerateBlueprintsResponse
import com.sprintstart.sprintstartbackend.onboarding.external.model.GenerateCompetencyGraphRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.GenerateOnboardingPathRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.GradeArtifactRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.GradeKnowledgeRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.GradeResult
import com.sprintstart.sprintstartbackend.onboarding.external.model.GraphProposalOutcome
import com.sprintstart.sprintstartbackend.onboarding.external.model.HireCompetencySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.LessonOutcome
import com.sprintstart.sprintstartbackend.onboarding.external.model.MatchHireToPoolRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.MineStarterWorkRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.OnboardingAiPathEvent
import com.sprintstart.sprintstartbackend.onboarding.external.model.ProposedStarterTaskSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.RankedStarterTaskSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.SkillAssessmentSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.StarterWorkOutcome
import com.sprintstart.sprintstartbackend.onboarding.external.model.SynthesizeLessonRequest
import com.sprintstart.sprintstartbackend.onboarding.model.exceptions.OnboardingAiException
import com.sprintstart.sprintstartbackend.shared.web.WebClient
import com.sprintstart.sprintstartbackend.shared.web.WebClientException
import kotlinx.coroutines.flow.Flow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI

@Component
class OnboardingAiClient(
    private val webClient: WebClient,
    private val applicationConfig: ApplicationConfig,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Opens an SSE stream against the AI service to generate a personalized onboarding path.
     *
     * The AI service is stateless, so the caller supplies the [blueprints] it should
     * personalize against. Malformed SSE chunks are logged and skipped rather than
     * terminating the stream.
     *
     * @param workingArea The user's working area scope (e.g. `backend`).
     * @param skills The user's leveled skill assessments; lets proficiency drive personalization.
     * @param blueprints The active blueprints the AI should personalize; empty yields a generic path.
     * @return A cold [Flow] of [OnboardingAiPathEvent]s emitted as generation progresses.
     */
    fun generatePath(
        workingArea: String,
        skills: List<SkillAssessmentSchema> = emptyList(),
        blueprints: List<BlueprintSchema> = emptyList(),
    ): Flow<OnboardingAiPathEvent> =
        webClient
            .post()
            .uri(uri("/api/v1/onboarding/path"))
            .body(
                GenerateOnboardingPathRequest(
                    workingArea = workingArea,
                    skills = skills,
                    blueprints = blueprints,
                ),
            ).stream()
            .perform<OnboardingAiPathEvent>(
                terminationMarkers = setOf("[DONE]"),
                onChunkError = { raw, err ->
                    logger.warn("Skipping malformed SSE chunk '{}': {}", raw, err.message)
                    true
                },
            )

    /**
     * Runs the AI service's batch blueprint generation job over the ingested corpus.
     *
     * The AI service is stateless: [active] (the backend's current active blueprints)
     * drives version numbering and lets the job skip an unchanged corpus. A non-2xx
     * response is wrapped in an [OnboardingAiException] carrying the upstream status/body.
     *
     * @param scopes The scopes to (re)generate, or `null` to refresh all known scopes.
     * @param active The backend's currently-active blueprints for the requested scopes.
     * @return The per-scope generation outcomes returned by the AI service.
     */
    suspend fun generateBlueprints(
        scopes: List<String>?,
        active: List<BlueprintSchema> = emptyList(),
    ): GenerateBlueprintsResponse =
        try {
            webClient
                .post()
                .uri(uri("/api/v1/onboarding/blueprints/generate"))
                .body(GenerateBlueprintsRequest(scopes = scopes, active = active))
                .sync()
                .perform<GenerateBlueprintsResponse>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to generate blueprints (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    /**
     * Runs one turn of the stateless adaptive skill-assessment interview.
     *
     * The backend owns session state; [request] carries the full transcript so far. A non-2xx
     * response is wrapped in an [OnboardingAiException] carrying the upstream status/body.
     *
     * @param request The candidate competencies, repo signal, transcript, and turn/cap state.
     * @return Either the next question (`done=false`) or the final placement (`done=true`).
     */
    suspend fun assessTurn(request: AssessmentTurnRequest): AssessmentTurnResponse =
        try {
            webClient
                .post()
                .uri(uri("/api/v1/onboarding/assessment/turn"))
                .body(request)
                .sync()
                .perform<AssessmentTurnResponse>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to run assessment turn (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    /**
     * Runs the AI service's batch competency graph proposal job over the ingested corpus.
     *
     * The AI service is stateless: [activeCompetencies]/[activeEdges] (the backend's current
     * live graph) drive dedup, and [lastFingerprint] drives idempotency -- there is no
     * "active proposal" object on this side to carry it, unlike blueprint generation. A non-2xx
     * response is wrapped in an [OnboardingAiException] carrying the upstream status/body.
     *
     * @param activeCompetencies The backend's current live competency nodes.
     * @param activeEdges The backend's current live prerequisite edges.
     * @param lastFingerprint The corpus fingerprint recorded from the most recent prior proposal, if any.
     * @return The proposal outcome returned by the AI service.
     */
    suspend fun proposeCompetencyGraph(
        activeCompetencies: List<ActiveCompetencySchema> = emptyList(),
        activeEdges: List<ActiveEdgeSchema> = emptyList(),
        lastFingerprint: String? = null,
    ): GraphProposalOutcome =
        try {
            webClient
                .post()
                .uri(uri("/api/v1/onboarding/competency-graph/propose"))
                .body(
                    GenerateCompetencyGraphRequest(
                        activeCompetencies = activeCompetencies,
                        activeEdges = activeEdges,
                        lastFingerprint = lastFingerprint,
                    ),
                ).sync()
                .perform<GraphProposalOutcome>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to propose competency graph (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    /**
     * Synthesizes a grounded lesson for one (competency, level) pair (Phase 3, #8).
     *
     * Heavyweight/offline, matching [proposeCompetencyGraph]: one retrieval + LLM pass, intended
     * for an admin-triggered content-authoring action, not the learner's request path.
     * [lastFingerprint] is whatever fingerprint the caller last recorded for this exact lesson
     * (idempotency is per-lesson, not corpus-wide). A non-2xx response is wrapped in an
     * [OnboardingAiException] carrying the upstream status/body.
     *
     * @param competencyKey The competency this lesson teaches.
     * @param competencyLabel The competency's display label.
     * @param competencyDescription Optional extra context for grounding the lesson.
     * @param level Target level to teach to (`beginner`/`intermediate`/`advanced`/`expert`).
     * @param lastFingerprint The corpus fingerprint recorded from the last synthesis of this lesson, if any.
     * @return The synthesis outcome returned by the AI service.
     */
    suspend fun synthesizeLesson(
        competencyKey: String,
        competencyLabel: String,
        competencyDescription: String = "",
        level: String = "beginner",
        lastFingerprint: String? = null,
    ): LessonOutcome =
        try {
            webClient
                .post()
                .uri(uri("/api/v1/onboarding/lessons/synthesize"))
                .body(
                    SynthesizeLessonRequest(
                        competencyKey = competencyKey,
                        competencyLabel = competencyLabel,
                        competencyDescription = competencyDescription,
                        level = level,
                        lastFingerprint = lastFingerprint,
                    ),
                ).sync()
                .perform<LessonOutcome>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to synthesize lesson (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    /**
     * Grades a free-text answer against a rubric via the AI service's LLM judge (Phase 3, #8).
     *
     * On the learner's request path, unlike [synthesizeLesson] -- called synchronously per
     * verification attempt. Only `knowledge`-type grading is delegated to the AI service;
     * `exact`/`attest` are graded locally in Kotlin (see `VerificationService`), so this method has
     * no `type` parameter. A non-2xx response is wrapped in an [OnboardingAiException] carrying the
     * upstream status/body -- callers should treat that as a retryable failure, not a graded fail,
     * since there is no safe local fallback for rubric-based judging.
     *
     * @param question The verification prompt shown to the learner.
     * @param rubric What a correct answer must demonstrate.
     * @param evidence Grounded evidence backing the rubric (the step's lesson content).
     * @param answer The learner's submitted answer.
     * @param attemptNo The 1-based attempt number, steering hint escalation on fail.
     * @return The grading result returned by the AI service.
     */
    suspend fun gradeKnowledge(
        question: String,
        rubric: String,
        evidence: String,
        answer: String,
        attemptNo: Int,
    ): GradeResult =
        try {
            webClient
                .post()
                .uri(uri("/api/v1/onboarding/verify"))
                .body(
                    GradeKnowledgeRequest(
                        question = question,
                        answer = answer,
                        attemptNo = attemptNo,
                        rubric = rubric,
                        evidence = evidence,
                    ),
                ).sync()
                .perform<GradeResult>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to grade knowledge answer (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    /**
     * Grades a hire-submitted PR's real state against a rubric via the AI service's LLM judge
     * (Phase 4, #9), the highest-rigor rung of the verification ladder.
     *
     * On the learner's request path, exactly like [gradeKnowledge] -- called synchronously per
     * verification attempt, after the backend has already gathered [evidence] from GitHub itself
     * (see `VerificationService.gradeArtifact`, which sources it via `GithubRepositoryApi`). The AI
     * service never re-derives facts like merge/CI status; it only judges whether the evidence's
     * content satisfies the rubric, and already short-circuits to a fail with no LLM call when
     * there's no evidence or CI is explicitly failing. A non-2xx response is wrapped in an
     * [OnboardingAiException] carrying the upstream status/body, same retryable-failure contract as
     * [gradeKnowledge].
     *
     * @param taskDescription The verification prompt describing what the PR must accomplish.
     * @param rubric What the PR's real state must demonstrate.
     * @param evidence The backend-gathered PR/repo state.
     * @return The grading result returned by the AI service.
     */
    suspend fun gradeArtifact(
        taskDescription: String,
        rubric: String,
        evidence: ArtifactEvidenceDto,
    ): GradeResult =
        try {
            webClient
                .post()
                .uri(uri("/api/v1/onboarding/verify"))
                .body(
                    GradeArtifactRequest(
                        question = taskDescription,
                        rubric = rubric,
                        artifactEvidence = evidence,
                    ),
                ).sync()
                .perform<GradeResult>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to grade artifact evidence (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    /**
     * Runs the AI service's batch starter-work mining job over the ingested corpus (Phase 4, #9).
     *
     * The AI service is stateless: [activeSourceIds] (issues already in the backend's pool,
     * proposed or approved) drive dedup, and [activeCompetencyKeys] (the backend's live graph
     * keys) ground each proposed task's competency tags -- a tag outside this set is dropped by
     * the AI service rather than invented. A non-2xx response is wrapped in an
     * [OnboardingAiException] carrying the upstream status/body.
     *
     * @param activeSourceIds Issues already in the backend's starter-work pool.
     * @param activeCompetencyKeys The backend's current live competency graph keys.
     * @return The mining outcome returned by the AI service.
     */
    suspend fun proposeStarterWork(
        activeSourceIds: List<String> = emptyList(),
        activeCompetencyKeys: List<String> = emptyList(),
    ): StarterWorkOutcome =
        try {
            webClient
                .post()
                .uri(uri("/api/v1/onboarding/starter-work/mine"))
                .body(
                    MineStarterWorkRequest(
                        activeSourceIds = activeSourceIds,
                        activeCompetencyKeys = activeCompetencyKeys,
                    ),
                ).sync()
                .perform<StarterWorkOutcome>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to propose starter work (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    /**
     * Ranks the starter-work pool by fit against one hire's competencies (Phase 4, #9).
     *
     * No LLM generation call on the AI side -- competency-key overlap is the primary score,
     * embeddings only break ties -- so this is cheap enough to call on the hire's request path. A
     * non-2xx response is wrapped in an [OnboardingAiException] carrying the upstream status/body.
     *
     * @param hireCompetencies The hire's freshly-built competencies (ledger).
     * @param pool The backend's current (PM-approved) starter-work pool.
     * @return The pool, ranked by fit, most relevant first.
     */
    suspend fun matchHireToPool(
        hireCompetencies: List<HireCompetencySchema>,
        pool: List<ProposedStarterTaskSchema>,
    ): List<RankedStarterTaskSchema> =
        try {
            webClient
                .post()
                .uri(uri("/api/v1/onboarding/starter-work/match"))
                .body(MatchHireToPoolRequest(hireCompetencies = hireCompetencies, pool = pool))
                .sync()
                .perform<List<RankedStarterTaskSchema>>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to match hire to starter-work pool (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    /** Builds an absolute URI for [path] against the configured AI service base URL. */
    private fun uri(path: String): URI = URI.create("${applicationConfig.ai.baseUrl}$path")
}
