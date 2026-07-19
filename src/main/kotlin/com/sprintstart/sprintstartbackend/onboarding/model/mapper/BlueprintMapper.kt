package com.sprintstart.sprintstartbackend.onboarding.model.mapper

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.external.model.BlueprintProvenanceSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.BlueprintSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.BlueprintStepSchema
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Blueprint
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStep
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintStepResponse

/** Maps one persisted [BlueprintStep] to its outward API response. */
fun BlueprintStep.toResponse(): BlueprintStepResponse =
    BlueprintStepResponse(
        id = stepId,
        title = title,
        description = description,
        requirement = requirement,
        invariant = invariant,
        proposalId = id,
        status = status,
    )

/**
 * Maps a persisted [Blueprint] entity to its outward API response, including the
 * ordered list of [BlueprintStepResponse] derived from the entity's steps.
 *
 * Unlike [toSchema], every step is included regardless of its [ProposalStatus] -- this is the PM
 * review surface itself, so a rejected step still needs to be visible (with its status) rather
 * than silently disappearing.
 */
fun Blueprint.toResponse(): BlueprintResponse =
    BlueprintResponse(
        scope = scope,
        version = version,
        steps = steps.map { it.toResponse() },
    )

/**
 * Maps a persisted [Blueprint] entity to the wire schema sent to the stateless AI
 * service. The corpus fingerprint is carried via [BlueprintProvenanceSchema] so the AI
 * can short-circuit regeneration when the corpus is unchanged.
 *
 * Excludes [ProposalStatus.REJECTED] steps -- this is what actually reaches personalization
 * (`OnboardingPersonalizationService`) and future regeneration context, so a step the PM rejected
 * must never count as part of the blueprint from here on. `PROPOSED` (never explicitly decided)
 * and `APPROVED` steps both still count, so a PM who never reviews individual steps sees no
 * behavior change.
 */
fun Blueprint.toSchema(): BlueprintSchema =
    BlueprintSchema(
        scope = scope,
        version = version,
        source = "generated",
        steps = steps
            .filter { it.status != ProposalStatus.REJECTED }
            .map { step ->
                BlueprintStepSchema(
                    id = step.stepId,
                    title = step.title,
                    description = step.description ?: "",
                    audience = step.audience.split(",").filter { it.isNotBlank() },
                    minExperience = step.minExperience,
                    requirement = step.requirement,
                    invariant = step.invariant,
                    competencyKey = step.competencyKey,
                )
            },
        provenance = corpusFingerprint?.let { BlueprintProvenanceSchema(corpusFingerprint = it) },
    )
