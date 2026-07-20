package com.sprintstart.sprintstartbackend.onboarding.model.mapper

import com.sprintstart.sprintstartbackend.onboarding.external.model.BaselineCompetencySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.BaselineSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.BlueprintProvenanceSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.BlueprintSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.BlueprintStepSchema
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Blueprint
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintCompetency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintCompetencyResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintResponse

/**
 * Maps one baseline entry to its outward API response, joining the competency in from the graph
 * for the label/description and the fallback bar.
 *
 * @param competency The graph node this entry selects, when it still exists. A selection can
 * outlive its competency (keys are stable, but a node can be removed), so this is nullable: the
 * entry is then shown under its bare key rather than disappearing from the review surface.
 */
fun BlueprintCompetency.toResponse(competency: Competency?): BlueprintCompetencyResponse =
    BlueprintCompetencyResponse(
        competencyKey = competencyKey,
        label = competency?.label ?: competencyKey,
        description = competency?.description,
        targetLevel = targetLevel ?: competency?.targetLevel ?: Competency.DEFAULT_TARGET_LEVEL,
        targetLevelOverridden = targetLevel != null,
        requirement = requirement.wireValue(),
        invariant = invariant,
        rationale = rationale,
        proposalId = id,
        status = status,
    )

/**
 * Maps a persisted [Blueprint] to its outward API response.
 *
 * Unlike [toSchema], every entry is included regardless of its status -- this is the PM review
 * surface itself, so a rejected entry still needs to be visible (with its status) rather than
 * silently disappearing.
 *
 * @param competenciesByKey The graph nodes the entries select, keyed by competency key.
 */
fun Blueprint.toResponse(competenciesByKey: Map<String, Competency> = emptyMap()): BlueprintResponse =
    BlueprintResponse(
        scope = scope,
        version = version,
        competencies = competencies.map { it.toResponse(competenciesByKey[it.competencyKey]) },
    )

/**
 * Maps a persisted [Blueprint] to the wire schema sent to the stateless AI service, so
 * regeneration can see what the current baseline already selects.
 *
 * Excludes rejected entries: this is what reaches regeneration context and personalization, so an
 * entry the PM rejected must never count as part of the baseline from here on.
 */
fun Blueprint.toSchema(): BaselineSchema =
    BaselineSchema(
        scope = scope,
        version = version,
        source = "generated",
        competencies = activeCompetencies().map { entry ->
            BaselineCompetencySchema(
                competencyKey = entry.competencyKey,
                targetLevel = entry.targetLevel,
                requirement = entry.requirement.wireValue(),
                invariant = entry.invariant,
                rationale = entry.rationale.orEmpty(),
            )
        },
        provenance = corpusFingerprint?.let { BlueprintProvenanceSchema(corpusFingerprint = it) },
    )

/**
 * Derives the step-shaped payload the AI's per-user path generation still consumes from this
 * baseline's competency selection.
 *
 * **Transitional.** The baseline stores a selection, not steps; path generation has not been
 * rewritten around competency-owned modules yet (backend#49/#53), so each selected competency is
 * presented to it as one step carrying that competency's own label and description. Every derived
 * step therefore carries a competency key by construction -- which is what makes a generated
 * module openable from its graph node at all.
 *
 * @param competenciesByKey The graph nodes the entries select, keyed by competency key. An entry
 * whose competency is missing from the graph is skipped: there is nothing to teach from.
 */
fun Blueprint.toPathSchema(competenciesByKey: Map<String, Competency>): BlueprintSchema =
    BlueprintSchema(
        scope = scope,
        version = version,
        source = "generated",
        steps = activeCompetencies().mapNotNull { entry ->
            val competency = competenciesByKey[entry.competencyKey] ?: return@mapNotNull null
            BlueprintStepSchema(
                id = entry.competencyKey,
                title = competency.label,
                description = competency.description.orEmpty(),
                requirement = entry.requirement.wireValue(),
                invariant = entry.invariant,
                competencyKey = entry.competencyKey,
            )
        },
        provenance = corpusFingerprint?.let { BlueprintProvenanceSchema(corpusFingerprint = it) },
    )
