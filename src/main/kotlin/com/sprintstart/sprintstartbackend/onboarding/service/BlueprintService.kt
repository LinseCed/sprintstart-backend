package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.model.Blueprint
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintDiffResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintOutcomeResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintStepResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.DiffChangeResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.DraftListResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.DraftSummaryResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.GenerateBlueprintsResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.VersionListResponse
import org.springframework.stereotype.Service

@Service
class BlueprintService(
    private val onboardingAiClient: OnboardingAiClient,
) {
    /**
     * Triggers AI blueprint generation for the given scopes and maps the outcomes to response DTOs.
     *
     * @param scopes Optional list of scope identifiers to generate; all scopes are used when null.
     * @return The generation outcomes for each requested scope.
     */
    suspend fun generateBlueprints(scopes: List<String>?): GenerateBlueprintsResponse {
        val response = onboardingAiClient.generateBlueprints(scopes)
        return GenerateBlueprintsResponse(
            outcomes = response.outcomes.map { outcome ->
                BlueprintOutcomeResponse(
                    scope = outcome.scope,
                    status = outcome.status,
                    message = outcome.message,
                )
            },
        )
    }

    /**
     * Returns all pending blueprint drafts awaiting review.
     *
     * @return A list of draft summaries.
     */
    suspend fun listDrafts(): DraftListResponse {
        val response = onboardingAiClient.listDrafts()
        return DraftListResponse(
            items = response.items.map { draft ->
                DraftSummaryResponse(
                    scope = draft.scope,
                    createdAt = draft.createdAt,
                    summary = draft.summary,
                )
            },
        )
    }

    /**
     * Returns the diff between the draft and the active blueprint for the given scope.
     *
     * @param scope Scope identifier of the draft to diff.
     * @return The diff containing changed steps and a blocked flag.
     */
    suspend fun getDraftDiff(scope: String): BlueprintDiffResponse {
        val response = onboardingAiClient.getDraftDiff(scope)
        return BlueprintDiffResponse(
            scope = response.scope,
            changes = response.changes.map { change ->
                DiffChangeResponse(
                    action = change.action,
                    stepId = change.stepId,
                    description = change.description,
                )
            },
            blocked = response.blocked,
        )
    }

    /**
     * Promotes the draft for the given scope to the active blueprint.
     *
     * @param scope Scope identifier of the draft to approve.
     * @return The newly active blueprint.
     */
    suspend fun approveDraft(scope: String): BlueprintResponse {
        val blueprint = onboardingAiClient.approveDraft(scope)
        return blueprint.toResponse()
    }

    /**
     * Deletes the pending draft for the given scope without promoting it.
     *
     * @param scope Scope identifier of the draft to discard.
     */
    suspend fun discardDraft(scope: String) {
        onboardingAiClient.discardDraft(scope)
    }

    /**
     * Returns all stored blueprint versions for the given scope.
     *
     * @param scope Scope identifier to list versions for.
     * @return The version list for the scope.
     */
    suspend fun listVersions(scope: String): VersionListResponse {
        val response = onboardingAiClient.listVersions(scope)
        return VersionListResponse(
            scope = response.scope,
            versions = response.versions,
        )
    }

    /**
     * Restores a previous blueprint version for the given scope.
     *
     * @param scope Scope identifier of the blueprint to roll back.
     * @param version Target version to restore.
     * @return The restored blueprint.
     */
    suspend fun rollback(scope: String, version: String): BlueprintResponse {
        val blueprint = onboardingAiClient.rollback(scope, version)
        return blueprint.toResponse()
    }

    private fun Blueprint.toResponse(): BlueprintResponse =
        BlueprintResponse(
            scope = this.scope,
            version = this.version,
            steps = this.steps.map { step ->
                BlueprintStepResponse(
                    id = step.id,
                    title = step.title,
                    description = step.description,
                )
            },
        )
}
