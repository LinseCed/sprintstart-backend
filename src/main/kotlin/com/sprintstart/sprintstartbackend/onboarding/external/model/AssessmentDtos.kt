package com.sprintstart.sprintstartbackend.onboarding.external.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request/response contract for the AI service's `POST /api/v1/onboarding/assessment/turn`
 * endpoint (Seam 1) — the stateless, per-turn adaptive interviewer. The backend owns the
 * transcript ([AssessmentTurnRequest.history]) and re-sends it on every turn; the AI service
 * either asks the next question (`done=false`) or returns a final per-competency placement
 * (`done=true`).
 */
@Serializable
data class CandidateCompetencySchema(
    val key: String,
    val label: String,
    val description: String = "",
    @SerialName("role_weight")
    val roleWeight: Double = 1.0,
)

@Serializable
data class RepoSignalSchema(
    val languages: List<String> = emptyList(),
    val frameworks: List<String> = emptyList(),
    val notable: List<String> = emptyList(),
)

@Serializable
data class AssessmentHistoryEntrySchema(
    val role: String,
    val content: String,
)

@Serializable
data class AssessmentTurnRequest(
    @SerialName("candidate_competencies")
    val candidateCompetencies: List<CandidateCompetencySchema>,
    @SerialName("repo_signal")
    val repoSignal: RepoSignalSchema = RepoSignalSchema(),
    val history: List<AssessmentHistoryEntrySchema> = emptyList(),
    val turn: Int,
    @SerialName("max_turns")
    val maxTurns: Int,
    @SerialName("must_finish")
    val mustFinish: Boolean = false,
)

@Serializable
data class AssessmentCoverageSchema(
    val key: String,
    val level: String? = null,
    val confidence: Double? = null,
)

@Serializable
data class AssessmentResultSchema(
    val key: String,
    val level: String = "beginner",
    val confidence: Double = 0.0,
    val evidence: String = "",
)

/**
 * [targets], [coverage], and [assessments] are nullable because the AI service's own schema
 * (`AssessmentTurnResponse` in `sprintstart-ai`) declares them `list[...] | None` by design --
 * `targets`/`coverage` are set only when `done=false` (still interviewing) and `assessments` only
 * when `done=true` (final placement). kotlinx.serialization's field defaults only apply when a key
 * is *absent*, not when it's explicitly `null`, so a non-nullable type here throws
 * `JsonDecodingException` on a real, valid response instead of quietly defaulting (confirmed via
 * a real assessment turn where `assessments` was explicitly `null`).
 */
@Serializable
data class AssessmentTurnResponse(
    val done: Boolean = false,
    val question: String? = null,
    val targets: List<String>? = null,
    val coverage: List<AssessmentCoverageSchema>? = null,
    val assessments: List<AssessmentResultSchema>? = null,
)
