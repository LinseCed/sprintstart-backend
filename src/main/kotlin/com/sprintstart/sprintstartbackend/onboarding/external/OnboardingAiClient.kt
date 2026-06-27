package com.sprintstart.sprintstartbackend.onboarding.external

import com.sprintstart.sprintstartbackend.ApplicationConfig
import com.sprintstart.sprintstartbackend.onboarding.external.model.AiPathGenerationEvent
import com.sprintstart.sprintstartbackend.onboarding.external.model.Blueprint
import com.sprintstart.sprintstartbackend.onboarding.external.model.BlueprintDiff
import com.sprintstart.sprintstartbackend.onboarding.external.model.DraftListResponse
import com.sprintstart.sprintstartbackend.onboarding.external.model.GenerateBlueprintsRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.GenerateBlueprintsResponse
import com.sprintstart.sprintstartbackend.onboarding.external.model.GenerateOnboardingPathRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.RollbackBlueprintRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.VersionListResponse
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
     * Opens an SSE stream to the AI service and returns path-generation events.
     *
     * @param workingArea Scope string identifying the user's working area.
     * @param experience Optional experience level of the user.
     * @return A cold [Flow] of [AiPathGenerationEvent] emitted by the AI service.
     */
    fun generatePath(workingArea: String, experience: String?): Flow<AiPathGenerationEvent> =
        webClient
            .post()
            .uri(uri("/api/v1/onboarding/path"))
            .body(GenerateOnboardingPathRequest(workingArea = workingArea, experience = experience))
            .stream()
            .perform<AiPathGenerationEvent>(
                terminationMarkers = setOf("[DONE]"),
                onChunkError = { raw, err ->
                    logger.warn("Skipping malformed SSE chunk '{}': {}", raw, err.message)
                    true
                },
            )

    /**
     * Requests the AI service to generate blueprints for the given scopes.
     *
     * @param scopes Optional list of scope identifiers to generate; all scopes are used when null.
     * @return The generation outcomes for each requested scope.
     * @throws OnboardingAiException When the AI service responds with a non-success status.
     */
    suspend fun generateBlueprints(scopes: List<String>?): GenerateBlueprintsResponse =
        try {
            webClient
                .post()
                .uri(uri("/api/v1/onboarding/blueprints/generate"))
                .body(GenerateBlueprintsRequest(scopes = scopes))
                .sync()
                .perform<GenerateBlueprintsResponse>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to generate blueprints (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    /**
     * Fetches all pending blueprint drafts from the AI service.
     *
     * @return A list of draft summaries.
     * @throws OnboardingAiException When the AI service responds with a non-success status.
     */
    suspend fun listDrafts(): DraftListResponse =
        try {
            webClient
                .get()
                .uri(uri("/api/v1/onboarding/blueprints/drafts"))
                .sync()
                .perform<DraftListResponse>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            throw OnboardingAiException(e.statusCode, e.body, "Failed to list drafts (HTTP ${e.statusCode}): ${e.body}")
        }

    /**
     * Fetches the diff between the draft and the active blueprint for the given scope.
     *
     * @param scope Scope identifier of the draft to diff.
     * @return The diff containing added, removed, and changed steps.
     * @throws OnboardingAiException When the AI service responds with a non-success status.
     */
    suspend fun getDraftDiff(scope: String): BlueprintDiff =
        try {
            webClient
                .get()
                .uri(uri("/api/v1/onboarding/blueprints/drafts/$scope/diff"))
                .sync()
                .perform<BlueprintDiff>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to get draft diff for '$scope' (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    /**
     * Approves the draft for the given scope, promoting it to the active blueprint.
     *
     * @param scope Scope identifier of the draft to approve.
     * @return The newly active blueprint.
     * @throws OnboardingAiException When the AI service responds with a non-success status.
     */
    suspend fun approveDraft(scope: String): Blueprint =
        try {
            webClient
                .post()
                .uri(uri("/api/v1/onboarding/blueprints/drafts/$scope/approve"))
                .sync()
                .perform<Blueprint>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to approve draft '$scope' (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    /**
     * Deletes the pending draft for the given scope.
     *
     * @param scope Scope identifier of the draft to discard.
     * @throws OnboardingAiException When the AI service responds with a non-success status.
     */
    suspend fun discardDraft(scope: String) {
        try {
            webClient
                .delete()
                .uri(uri("/api/v1/onboarding/blueprints/drafts/$scope"))
                .sync()
                .performRaw()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to discard draft '$scope' (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }
    }

    /**
     * Fetches all stored blueprint versions for the given scope.
     *
     * @param scope Scope identifier to list versions for.
     * @return The version list for the scope.
     * @throws OnboardingAiException When the AI service responds with a non-success status.
     */
    suspend fun listVersions(scope: String): VersionListResponse =
        try {
            webClient
                .get()
                .uri(uri("/api/v1/onboarding/blueprints/$scope/versions"))
                .sync()
                .perform<VersionListResponse>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to list versions for '$scope' (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    /**
     * Rolls back the blueprint for the given scope to the specified version.
     *
     * @param scope Scope identifier of the blueprint to roll back.
     * @param version Target version to restore.
     * @return The restored blueprint.
     * @throws OnboardingAiException When the AI service responds with a non-success status.
     */
    suspend fun rollback(scope: String, version: String): Blueprint =
        try {
            webClient
                .post()
                .uri(uri("/api/v1/onboarding/blueprints/$scope/rollback"))
                .body(RollbackBlueprintRequest(version = version))
                .sync()
                .perform<Blueprint>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to rollback '$scope' to version '$version' (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    private fun uri(path: String): URI = URI.create("${applicationConfig.ai.baseUrl}$path")
}
