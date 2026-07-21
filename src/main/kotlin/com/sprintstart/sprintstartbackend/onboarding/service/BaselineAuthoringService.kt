package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Blueprint
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintCompetency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintOrigin
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintRequirement
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStatus
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toResponse
import com.sprintstart.sprintstartbackend.onboarding.model.request.blueprint.SetBaselineEntryRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintCompetencyResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.BlueprintRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * The baseline a PM authors directly by marking approved competencies "expected on this project" —
 * the fold that removes the second AI round trip (D1). It writes the project's ACTIVE baseline in
 * place instead of minting a proposal, because a PM's own selection is not something that needs
 * proposing and reviewing back to them.
 *
 * The AI baseline generation flow ([BlueprintService]) is untouched and coexists; both write the
 * same `BlueprintCompetency` rows a path projection reads, so a baseline can be part-mined,
 * part-authored without either side knowing about the other.
 */
@Service
class BaselineAuthoringService(
    private val blueprintRepository: BlueprintRepository,
    private val competencyRepository: CompetencyRepository,
) {
    @Transactional(readOnly = true)
    fun listEntries(projectId: UUID): List<BlueprintCompetencyResponse> {
        val blueprint = activeBlueprint(projectId) ?: return emptyList()
        return blueprint.activeCompetencies().map { entry ->
            entry.toResponse(competencyRepository.findByKey(entry.competencyKey))
        }
    }

    @Transactional
    fun setEntry(
        projectId: UUID,
        competencyKey: String,
        request: SetBaselineEntryRequest,
    ): BlueprintCompetencyResponse {
        val competency = competencyRepository.findByKey(competencyKey)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No competency '$competencyKey' in the graph. Approve it before adding it to a baseline.",
            )
        val requirement = request.requirement
            ?.let { BlueprintRequirement.fromWire(it) }
            ?: BlueprintRequirement.REQUIRED

        val blueprint = activeBlueprint(projectId) ?: createActiveBlueprint(projectId)
        val existing = blueprint.competencies.firstOrNull {
            it.competencyKey == competencyKey && it.status != ProposalStatus.REJECTED
        }
        val entry = if (existing != null) {
            existing.apply {
                targetLevel = request.targetLevel
                this.requirement = requirement
                invariant = request.invariant ?: invariant
                status = ProposalStatus.APPROVED
                decidedAt = Instant.now()
            }
        } else {
            val nextPosition = (blueprint.competencies.maxOfOrNull { it.position } ?: -1) + 1
            BlueprintCompetency(
                blueprint = blueprint,
                competencyKey = competencyKey,
                targetLevel = request.targetLevel,
                requirement = requirement,
                invariant = request.invariant ?: false,
                rationale = null,
                position = nextPosition,
                status = ProposalStatus.APPROVED,
                decidedAt = Instant.now(),
            ).also { blueprint.competencies.add(it) }
        }
        blueprintRepository.save(blueprint)
        return entry.toResponse(competency)
    }

    @Transactional
    fun removeEntry(projectId: UUID, competencyKey: String) {
        val blueprint = activeBlueprint(projectId) ?: return
        val entry = blueprint.competencies.firstOrNull { it.competencyKey == competencyKey } ?: return
        if (entry.invariant) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "'$competencyKey' is a protected mandate and cannot be removed from the baseline.",
            )
        }
        blueprint.competencies.remove(entry) // orphanRemoval deletes the row
        blueprintRepository.save(blueprint)
    }

    private fun activeBlueprint(projectId: UUID): Blueprint? =
        blueprintRepository.findByProjectIdAndScopeAndStatus(projectId, DEFAULT_SCOPE, BlueprintStatus.ACTIVE)

    private fun createActiveBlueprint(projectId: UUID): Blueprint =
        blueprintRepository.save(
            Blueprint(
                projectId = projectId,
                scope = DEFAULT_SCOPE,
                version = PM_AUTHORED_VERSION,
                status = BlueprintStatus.ACTIVE,
                origin = BlueprintOrigin.PM,
            ),
        )

    private companion object {
        // One baseline per project; the role/area split isn't used by direct authoring.
        const val DEFAULT_SCOPE = "global"

        // A PM-authored baseline is edited in place, so it never accrues the numbered versions the
        // AI proposal flow assigns; a single stable label is enough to identify the live one.
        const val PM_AUTHORED_VERSION = "pm-authored"
    }
}
