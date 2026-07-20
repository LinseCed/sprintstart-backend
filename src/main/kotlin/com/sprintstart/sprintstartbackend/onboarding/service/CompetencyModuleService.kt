package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ContentProvenance
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ModulePageKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ModuleStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyModule
import com.sprintstart.sprintstartbackend.onboarding.model.entity.ModulePage
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Verification
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toResponse
import com.sprintstart.sprintstartbackend.onboarding.model.request.module.CreateCompetencyModuleRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.module.CreateModulePageRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.module.ReorderModulePagesRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.module.UpdateCompetencyModuleRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.module.UpdateModulePageRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.module.CompetencyModuleResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.module.CompetencyModulesResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.module.ModulePageResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyModuleRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.ModulePageRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.VerificationRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * Authoring and lifecycle for [CompetencyModule] — the shared content a competency teaches.
 *
 * Proposal-only, the same invariant blueprints and graph proposals hold: authoring never changes
 * what a hire sees. A module is written as a [ModuleStatus.DRAFT], offered for review as
 * [ModuleStatus.PROPOSED], and only [approve] makes one live — archiving whatever was live before,
 * so exactly one version per `(competency, project)` is ever [ModuleStatus.ACTIVE].
 *
 * Editing a live module means creating a new version from it, not mutating it: a hire halfway
 * through a module should not have the ground move under them, and the previous version stays as
 * the record of what earlier hires were actually taught.
 */
@Suppress("TooManyFunctions")
@Service
class CompetencyModuleService(
    private val competencyModuleRepository: CompetencyModuleRepository,
    private val modulePageRepository: ModulePageRepository,
    private val competencyRepository: CompetencyRepository,
    private val verificationRepository: VerificationRepository,
    private val userApi: UserApi,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Starts a new module version for `(competencyKey, projectId)`.
     *
     * @param copyFromActive When true, the current ACTIVE version's pages and check are copied in,
     * so editing what is live starts from what is live rather than from nothing.
     * @throws ResponseStatusException 404 if the competency does not exist in the graph — a module
     * teaches a node, so there has to be one.
     */
    @Transactional
    fun create(request: CreateCompetencyModuleRequest): CompetencyModuleResponse {
        val competency = competencyRepository.findByKey(request.competencyKey)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No competency found with key: ${request.competencyKey}",
            )

        val nextVersion = competencyModuleRepository
            .findAllByCompetencyKeyAndProjectIdOrderByVersionDesc(request.competencyKey, request.projectId)
            .firstOrNull()
            ?.version
            ?.plus(1)
            ?: 1

        val module = CompetencyModule(
            competencyKey = request.competencyKey,
            projectId = request.projectId,
            version = nextVersion,
            status = ModuleStatus.DRAFT,
            origin = ContentProvenance.PM,
            title = request.title,
            summary = request.summary,
        )

        if (request.copyFromActive) {
            copyActiveInto(module)
        }

        competencyModuleRepository.save(module)
        return module.toResponse(competency.label, verificationRepository.findByModuleId(module.id)?.type)
    }

    /** Lists a project's modules in one status, for the authoring surface. */
    @Transactional(readOnly = true)
    fun list(projectId: UUID, status: ModuleStatus): CompetencyModulesResponse {
        val modules = competencyModuleRepository
            .findAllByProjectIdAndStatusOrderByCompetencyKeyAsc(projectId, status)
        return CompetencyModulesResponse(modules = modules.map { it.toResponseWithJoins() })
    }

    @Transactional(readOnly = true)
    fun get(moduleId: UUID): CompetencyModuleResponse = findModule(moduleId).toResponseWithJoins()

    /**
     * Returns a live module for the hire opening it.
     *
     * The access rule is project membership, not "this row belongs to you": the module is shared,
     * so there is no per-user copy to match a hire against. A module that is not live is reported
     * as absent rather than forbidden -- an unpublished draft is not something a hire is being
     * denied, it is something that does not exist for them.
     *
     * @throws ResponseStatusException 404 if no live module has that id; 403 if the user is not a
     * member of its project.
     */
    @Transactional(readOnly = true)
    fun getForMe(authId: String, moduleId: UUID): CompetencyModuleResponse {
        val module = findModule(moduleId)
        if (module.status != ModuleStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No module found with id: $moduleId")
        }
        if (!userApi.userHasAccessToProject(authId, module.projectId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "User is not a member of this module's project")
        }
        return module.toResponseWithJoins()
    }

    @Transactional
    fun update(moduleId: UUID, request: UpdateCompetencyModuleRequest): CompetencyModuleResponse {
        val module = findEditableModule(moduleId)
        request.title?.let { module.title = it }
        request.summary?.let { module.summary = it }
        module.updatedAt = Instant.now()
        return module.toResponseWithJoins()
    }

    /**
     * Offers a draft for review. Separate from [approve] so the author and the approver can be
     * different people — the whole point of a proposal-only lifecycle.
     */
    @Transactional
    fun propose(moduleId: UUID): CompetencyModuleResponse {
        val module = findModule(moduleId)
        if (module.status != ModuleStatus.DRAFT) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Only a DRAFT module can be proposed")
        }
        module.status = ModuleStatus.PROPOSED
        module.updatedAt = Instant.now()
        return module.toResponseWithJoins()
    }

    /**
     * Makes this version the live module for its competency and project, archiving the previous
     * one. This is the only path by which what a hire sees changes.
     *
     * @throws ResponseStatusException 409 if the module is already archived, or if it has no
     * pages — approving an empty module would put a node on every hire's path that opens to
     * nothing.
     */
    @Transactional
    fun approve(moduleId: UUID): CompetencyModuleResponse {
        val module = findModule(moduleId)
        if (module.status == ModuleStatus.ARCHIVED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "An archived module cannot be approved")
        }
        if (module.status == ModuleStatus.ACTIVE) {
            return module.toResponseWithJoins()
        }
        if (module.pages.isEmpty()) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "A module with no pages cannot be approved")
        }

        competencyModuleRepository
            .findByCompetencyKeyAndProjectIdAndStatus(module.competencyKey, module.projectId, ModuleStatus.ACTIVE)
            ?.let {
                it.status = ModuleStatus.ARCHIVED
                it.updatedAt = Instant.now()
            }

        module.status = ModuleStatus.ACTIVE
        module.updatedAt = Instant.now()
        return module.toResponseWithJoins()
    }

    /** Archives a version without activating it; whatever is live stays live. */
    @Transactional
    fun reject(moduleId: UUID, reason: String? = null): CompetencyModuleResponse {
        val module = findModule(moduleId)
        if (module.status == ModuleStatus.ACTIVE) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "The live module cannot be rejected; approve a replacement instead",
            )
        }
        module.status = ModuleStatus.ARCHIVED
        module.updatedAt = Instant.now()
        logger.info(
            "Rejected module {} v{} for {} (reason: {})",
            module.id,
            module.version,
            module.competencyKey,
            reason ?: "none given",
        )
        return module.toResponseWithJoins()
    }

//  ========================== Pages ==========================

    @Transactional
    fun addPage(moduleId: UUID, request: CreateModulePageRequest): ModulePageResponse {
        val module = findEditableModule(moduleId)
        val page = ModulePage(
            module = module,
            kind = request.kind,
            title = request.title,
            body = request.body,
            position = request.position?.coerceIn(0, module.pages.size) ?: module.pages.size,
            provenance = ContentProvenance.PM,
        )
        module.pages.add(page)
        normalizePositions(module, movedTo = page)
        module.updatedAt = Instant.now()
        return page.toResponse()
    }

    @Transactional
    fun updatePage(pageId: UUID, request: UpdateModulePageRequest): ModulePageResponse {
        val page = findPage(pageId)
        requireEditable(page.module)
        request.kind?.let { page.kind = it }
        request.title?.let { page.title = it }
        request.body?.let { page.body = it }
        // A page a human touched is a page re-synthesis must leave alone. Editing is what makes it
        // PM-authored, whoever originally wrote it.
        page.provenance = ContentProvenance.PM
        page.updatedAt = Instant.now()
        page.module.updatedAt = Instant.now()
        return page.toResponse()
    }

    @Transactional
    fun deletePage(pageId: UUID) {
        val page = findPage(pageId)
        val module = page.module
        requireEditable(module)
        module.pages.remove(page)
        normalizePositions(module)
        module.updatedAt = Instant.now()
    }

    /**
     * Applies a complete new page order in one call.
     *
     * @throws ResponseStatusException 400 if [ReorderModulePagesRequest.pageIds] is not exactly the
     * module's current pages. A partial list is ambiguous about where the omitted pages go, and
     * silently appending them is how a reorder quietly loses a page.
     */
    @Transactional
    fun reorderPages(moduleId: UUID, request: ReorderModulePagesRequest): CompetencyModuleResponse {
        val module = findEditableModule(moduleId)
        val current = module.pages.associateBy { it.id }
        if (request.pageIds.toSet() != current.keys || request.pageIds.size != current.size) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "pageIds must list every page of this module exactly once",
            )
        }
        request.pageIds.forEachIndexed { index, pageId ->
            current.getValue(pageId).position = index
        }
        module.pages.sortBy { it.position }
        module.updatedAt = Instant.now()
        return module.toResponseWithJoins()
    }

//  ========================== Helpers ==========================

    /**
     * Copies the live version's pages and check into a new draft.
     *
     * Page provenance is carried over rather than reset: a PM-authored page stays PM-authored
     * across versions, which is what lets re-synthesis keep leaving it alone.
     */
    private fun copyActiveInto(module: CompetencyModule) {
        val active = competencyModuleRepository.findByCompetencyKeyAndProjectIdAndStatus(
            module.competencyKey,
            module.projectId,
            ModuleStatus.ACTIVE,
        ) ?: return

        active.pages.forEach { page ->
            module.pages.add(
                ModulePage(
                    module = module,
                    kind = page.kind,
                    title = page.title,
                    body = page.body,
                    position = page.position,
                    provenance = page.provenance,
                ),
            )
        }
        module.corpusFingerprint = active.corpusFingerprint

        verificationRepository.findByModuleId(active.id)?.let { check ->
            verificationRepository.save(
                Verification(
                    moduleId = module.id,
                    type = check.type,
                    prompt = check.prompt,
                    rubric = check.rubric,
                    canonicalAnswer = check.canonicalAnswer,
                    repositoryConnectionId = check.repositoryConnectionId,
                    competencyKey = check.competencyKey,
                    level = check.level,
                ),
            )
        }
    }

    /**
     * Renumbers positions to a dense 0..n-1 sequence, keeping [movedTo] at the slot it asked for.
     * Positions are an ordering, not identifiers, so gaps and ties are corrected on every write
     * rather than tolerated until they surface as an arbitrary render order.
     */
    private fun normalizePositions(module: CompetencyModule, movedTo: ModulePage? = null) {
        val ordered = module.pages.sortedWith(
            compareBy({ it.position }, { if (it === movedTo) 0 else 1 }),
        )
        ordered.forEachIndexed { index, page -> page.position = index }
        module.pages.sortBy { it.position }
    }

    /**
     * A live or archived version is a record of what hires were taught, so it is not edited in
     * place — a new version is created from it instead.
     */
    private fun requireEditable(module: CompetencyModule) {
        if (module.status == ModuleStatus.ACTIVE || module.status == ModuleStatus.ARCHIVED) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Module ${module.id} is ${module.status}; create a new version to change it",
            )
        }
    }

    private fun findEditableModule(moduleId: UUID): CompetencyModule =
        findModule(moduleId).also { requireEditable(it) }

    private fun findModule(moduleId: UUID): CompetencyModule =
        competencyModuleRepository
            .findById(moduleId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No module found with id: $moduleId") }

    private fun findPage(pageId: UUID): ModulePage =
        modulePageRepository
            .findById(pageId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No module page found with id: $pageId") }

    private fun CompetencyModule.toResponseWithJoins(): CompetencyModuleResponse =
        toResponse(
            competencyLabel = competencyRepository.findByKey(competencyKey)?.label,
            verificationType = verificationRepository.findByModuleId(id)?.type,
        )
}

/** The page kinds that carry lesson prose, used as grounded evidence when grading a check. */
val LESSON_PAGE_KINDS = setOf(ModulePageKind.CONTEXT, ModulePageKind.LESSON, ModulePageKind.WALKTHROUGH)
