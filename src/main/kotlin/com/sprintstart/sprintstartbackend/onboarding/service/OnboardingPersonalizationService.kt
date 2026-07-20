package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.VerificationType
import com.sprintstart.sprintstartbackend.onboarding.external.model.BlueprintSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.OnboardingAiPathEvent
import com.sprintstart.sprintstartbackend.onboarding.external.model.SkillAssessmentSchema
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingPath
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Verification
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toEntities
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toGetForUserResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toPathSchema
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.OnboardingSseEvent
import com.sprintstart.sprintstartbackend.onboarding.repository.BlueprintRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingPathRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserCompetencyStateRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.VerificationRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import com.sprintstart.sprintstartbackend.user.external.dto.toAiScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class OnboardingPersonalizationService(
    private val onboardingAiClient: OnboardingAiClient,
    private val onboardingPathRepository: OnboardingPathRepository,
    private val blueprintRepository: BlueprintRepository,
    private val userCompetencyStateRepository: UserCompetencyStateRepository,
    private val competencyRepository: CompetencyRepository,
    private val verificationRepository: VerificationRepository,
    private val userApi: UserApi,
    transactionManager: PlatformTransactionManager,
) {
    // Used for the short DB phases only (reading blueprints, deleting the previous path).
    // No AI generation happens here, so no connection is pinned for a slow call.
    private val txTemplate = TransactionTemplate(transactionManager)

    /**
     * Generates a personalized onboarding path for the user identified by [authId].
     *
     * The user's profile (assigned project role) is read up front so a missing user
     * fails fast with 404 before any streaming begins. The returned cold [Flow], once
     * collected, reads the ACTIVE baseline blueprints for the user's scopes
     * (`global` + `area:<role-slug>`). Blueprint authoring is an explicit offline action
     * (propose then PM approve); personalization never generates blueprints on the user's
     * clock. If a required scope has no ACTIVE blueprint, a single `error` event is emitted
     * and nothing else happens — the user's existing path is left intact. Otherwise the
     * previous path (the projection) is deleted and the AI personalization events are
     * streamed; a `path` event is persisted before being forwarded.
     *
     * The path is a disposable projection: this method may delete and rebuild it, but it
     * never touches the durable `UserCompetencyState` ledger.
     *
     * @param authId External authentication identifier from the JWT subject.
     * @param projectId The project this hire is onboarding for. Onboarding is per-project: the
     * baseline, the resolved role scope and the rebuilt path are all scoped to this project, and a
     * user in several projects onboards each independently.
     * @return A cold [Flow] of [OnboardingSseEvent] emitted during path generation.
     * @throws ResponseStatusException 404 if no user exists for [authId], 403 if the user is not a
     * member of [projectId], 400 if the user holds no role in that project.
     */
    fun personalize(authId: String, projectId: UUID): Flow<OnboardingSseEvent> {
        val profile = userApi
            .getOnboardingProfileByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User with authId: $authId not found") }

        if (!userApi.userHasAccessToProject(authId, projectId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "User is not a member of project: $projectId")
        }

        // Resolve the role the user holds *within this project* (not the project-agnostic role set)
        // to pick the blueprint area scope. A user typically holds one role per project; if several,
        // the first drives the scope for now (multi-role-in-project is a deferred refinement).
        val rolesInProject = userApi.getProjectRolesForUser(profile.id, projectId)
        if (rolesInProject.isEmpty()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "User has no role in project: $projectId",
            )
        }

        val scope = rolesInProject.first().toAiScope()
        val requiredScopes = listOf("global", "area:$scope")

        return flow {
            // Proficiency comes from the durable competency ledger (chat placement + passed
            // verifications), not the retired self-reported skill wizard -- the ledger is the
            // one skill store the current UI actually writes. The ledger is global (a proven skill
            // transfers across projects), so it is not project-scoped here.
            val skills = withContext(Dispatchers.IO) {
                txTemplate.execute { loadLedgerSkills(profile.id) }
            }.orEmpty()
            val blueprints = withContext(Dispatchers.IO) {
                txTemplate.execute { loadActiveBlueprints(projectId, requiredScopes) }
            }.orEmpty()

            val loadedScopes = blueprints.map { it.scope }.toSet()
            val missingScopes = requiredScopes.filterNot { it in loadedScopes }
            if (missingScopes.isNotEmpty()) {
                emit(
                    OnboardingSseEvent(
                        type = "error",
                        message = "Onboarding baseline for ${missingScopes.joinToString(", ")} is not ready yet",
                    ),
                )
                return@flow
            }

            withContext(Dispatchers.IO) {
                txTemplate.executeWithoutResult {
                    onboardingPathRepository.deleteByUserIdAndProjectId(profile.id, projectId)
                }
            }

            emitAll(
                onboardingAiClient
                    .generatePath(scope, skills, blueprints)
                    .map { event -> event.toSseEvent(profile.id, projectId) },
            )
        }.catch { e ->
            emit(OnboardingSseEvent(type = "error", message = e.message))
        }
    }

    /**
     * Maps an AI [OnboardingAiPathEvent] to the outward [OnboardingSseEvent]. A `path`
     * event's generated path is persisted (in its own repository transaction) before the
     * saved view is forwarded.
     *
     * @param userId [UUID] The id of the user of the onboarding path.
     * @param projectId [UUID] The project the generated path belongs to.
     */
    private fun OnboardingAiPathEvent.toSseEvent(userId: UUID, projectId: UUID): OnboardingSseEvent =
        when (type) {
            "stage" -> {
                OnboardingSseEvent(type = "stage", name = name, detail = detail)
            }

            "path" -> {
                val savedPath = path?.let { aiPath ->
                    val entity = aiPath.toEntities(userId, projectId)
                    onboardingPathRepository.save(entity)
                    createDefaultVerifications(entity)
                    entity.toGetForUserResponse()
                }
                OnboardingSseEvent(type = "path", path = savedPath)
            }

            "error" -> {
                OnboardingSseEvent(type = "error", message = message)
            }

            else -> {
                OnboardingSseEvent(type = type)
            }
        }

    /**
     * Loads the ACTIVE baselines for [scopes] within [projectId] and derives the step-shaped
     * payload the AI's path generation consumes from their competency selections. For each scope
     * the project's own ACTIVE baseline wins; when the project has none, the unscoped
     * (null-project) legacy/global baseline is used as a fallback so projects without their own
     * baseline yet still onboard. Scopes with neither are skipped. Must be called within a
     * transaction so each baseline's lazy entries can be read.
     *
     * The step shape is transitional -- see [toPathSchema]. Because every derived step comes from a
     * selected competency, every generated step carries a competency key, and therefore gets a
     * default check and an openable graph node.
     *
     * @param projectId The project whose baseline should be preferred.
     * @param scopes A list of scopes to load active baselines from.
     */
    private fun loadActiveBlueprints(projectId: UUID, scopes: List<String>): List<BlueprintSchema> {
        val blueprints = scopes.mapNotNull { scope ->
            blueprintRepository.findByProjectIdAndScopeAndStatus(projectId, scope, BlueprintStatus.ACTIVE)
                ?: blueprintRepository.findByProjectIdIsNullAndScopeAndStatus(scope, BlueprintStatus.ACTIVE)
        }
        val keys = blueprints.flatMap { it.activeCompetencies() }.map { it.competencyKey }.toSet()
        val competenciesByKey = if (keys.isEmpty()) {
            emptyMap()
        } else {
            competencyRepository.findAllByKeyIn(keys).associateBy { it.key }
        }
        return blueprints.map { it.toPathSchema(competenciesByKey) }
    }

    /**
     * Maps the user's competency ledger to the AI's skill schema: competency label (falling back
     * to the stable key) plus the level name. Level 0 rows (unknown/not yet placed) are skipped --
     * they are not proficiency.
     */
    private fun loadLedgerSkills(userId: UUID): List<SkillAssessmentSchema> {
        val states = userCompetencyStateRepository.findAllByUserId(userId).filter { it.level > 0 }
        if (states.isEmpty()) return emptyList()

        val labelsByKey = competencyRepository
            .findAllByKeyIn(states.map { it.competencyKey })
            .associate { it.key to it.label }

        return states.map { state ->
            SkillAssessmentSchema(
                name = labelsByKey[state.competencyKey] ?: state.competencyKey,
                level = LEVEL_NAMES.getValue(state.level.coerceIn(1, 4)),
            )
        }
    }

    /**
     * Gives every competency-tagged step a graded check, so generating a path also produces
     * openable modules.
     *
     * A graph node becomes a module only when some [Verification] carries its competency key --
     * that is the sole bridge from the competency graph to a step. Nothing created those rows
     * except a PM configuring each step by hand, so a freshly generated path had a full graph and
     * *zero* openable nodes.
     *
     * The default is a `KNOWLEDGE` check rubric'd against the step's expected outcome: gradeable
     * immediately, and already what the step claims the hire should be able to do. A PM can replace
     * it with a stricter tier (`ARTIFACT`, `EXACT`, ...) through the existing upsert -- this only
     * guarantees a check *exists*. Steps with no competency key are skipped: there would be no node
     * to open them from.
     */
    private fun createDefaultVerifications(path: OnboardingPath) {
        val steps = path.phases
            .flatMap { it.steps }
            .filter { !it.competencyKey.isNullOrBlank() }
        if (steps.isEmpty()) {
            return
        }

        verificationRepository.saveAll(
            steps.map { step ->
                Verification(
                    stepId = step.id,
                    type = VerificationType.KNOWLEDGE,
                    prompt = "Show that you can: " + step.expectedOutcome.ifBlank { step.title },
                    rubric = step.expectedOutcome.ifBlank { step.description },
                    competencyKey = step.competencyKey.orEmpty(),
                    level = DEFAULT_VERIFICATION_LEVEL,
                )
            },
        )
    }

    private companion object {
        // Inverse of AssessmentService.LEVEL_RANKS -- ledger 1..4 back to the AI's level names.
        val LEVEL_NAMES = mapOf(
            1 to "beginner",
            2 to "intermediate",
            3 to "advanced",
            4 to "expert",
        )

        // Must clear Competency.DEFAULT_TARGET_LEVEL: passing a generated check is how a hire
        // masters a node that has no PM-authored bar, so a check pitched *below* the default bar
        // would mean doing everything the path asks and completing nothing. The two defaults move
        // together -- raising one without the other silently strands every generated module.
        // A PM adjusts either per node/step once graph editing lands (backend#50).
        val DEFAULT_VERIFICATION_LEVEL = LEVEL_NAMES.getValue(Competency.DEFAULT_TARGET_LEVEL)
    }
}
