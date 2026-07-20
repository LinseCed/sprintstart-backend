package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.ingestion.external.ArtifactIngestionApi
import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.OrientationStep
import com.sprintstart.sprintstartbackend.onboarding.external.model.OrientationOutcome
import com.sprintstart.sprintstartbackend.onboarding.external.model.OrientationPacketSchema
import com.sprintstart.sprintstartbackend.onboarding.model.entity.TaskOrientationCitation
import com.sprintstart.sprintstartbackend.onboarding.model.entity.TaskOrientationPacket
import com.sprintstart.sprintstartbackend.onboarding.model.entity.TaskOrientationSection
import com.sprintstart.sprintstartbackend.onboarding.model.entity.TaskOrientationSource
import com.sprintstart.sprintstartbackend.onboarding.model.exceptions.OnboardingAiException
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.orientation.MyOrientationResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.orientation.OrientationPacketResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.StarterWorkTaskProposalRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.TaskOrientationPacketRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.TaskZeroAssignmentRepository
import com.sprintstart.sprintstartbackend.user.external.ProjectMembershipApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.util.UUID

/**
 * Serving of task-scoped orientation: what this project already says about doing the task a hire has.
 *
 * The interesting decisions are all about *not* lying to the reader:
 *
 * * **The cache is validated, never trusted.** Every read sends the fingerprint of the corpus the
 *   cached packet was built from. An unchanged corpus comes back `unchanged` with no retrieval or
 *   LLM pass and the cache is served; a corpus that has moved is re-assembled. A packet describing
 *   code that has since changed is worse than no packet, because a hire cannot tell.
 * * **`skipped` deletes the cache.** The AI service compared against the *current* corpus and could
 *   not ground a packet, so whatever is cached describes a corpus that no longer exists. Keeping it
 *   would be exactly the stale-serving the point above forbids.
 * * **A transport failure serves the cache.** Unlike `skipped`, an unreachable AI service is no
 *   evidence at all about staleness — so the last known good packet is still the most honest thing
 *   available, and losing orientation to a flaky call helps nobody.
 * * **Nothing is ever fabricated.** "No packet" is an ordinary returned state carrying the reason —
 *   never an empty packet, never an error (cf. the `fetchStep` mock-fallback bug, frontend#26).
 *
 * Reading orientation is not a gate and never assigns anything: the hire's current task is read
 * straight from the assignment table rather than through [TaskZeroService.getForHire], which assigns
 * on read. Opening the help must not be what hands somebody their first task.
 */
@Service
class TaskOrientationService(
    private val taskOrientationPacketRepository: TaskOrientationPacketRepository,
    private val taskZeroAssignmentRepository: TaskZeroAssignmentRepository,
    private val starterWorkTaskProposalRepository: StarterWorkTaskProposalRepository,
    private val projectMembershipApi: ProjectMembershipApi,
    private val artifactIngestionApi: ArtifactIngestionApi,
    private val onboardingAiClient: OnboardingAiClient,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    // The AI call is a long-running suspend operation, so it must not run inside a transaction --
    // same read-tx -> AI -> write-tx split as CompetencyModuleService.
    private val txTemplate = TransactionTemplate(transactionManager)
    private val readTxTemplate = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Orientation for the task the hire currently has on [projectId].
     *
     * @throws ResponseStatusException 404 when the hire is not a member of the project.
     */
    suspend fun getForHire(hireId: UUID, projectId: UUID): MyOrientationResponse {
        val context = withContext(Dispatchers.IO) {
            readTxTemplate.execute { loadContext(hireId, projectId) }
        } ?: return NO_TASK

        val outcome = try {
            onboardingAiClient.assembleOrientation(
                taskTitle = context.title,
                taskBody = context.body,
                labels = context.labels,
                // Which paths a task touches is not knowable from an issue -- it becomes real when
                // there is a pull request to read it off. Sent empty rather than guessed at.
                touchedPaths = emptyList(),
                lastFingerprint = context.cachedFingerprint,
            )
        } catch (e: OnboardingAiException) {
            logger.warn("Orientation assembly unavailable for task {}: {}", context.proposalId, e.message)
            return context.cachedOrEmpty("Orientation is temporarily unavailable")
        }

        return withContext(Dispatchers.IO) {
            txTemplate.execute { apply(context, outcome) }!!
        }
    }

    private fun apply(context: TaskContext, outcome: OrientationOutcome): MyOrientationResponse {
        val packet = outcome.packet
        return when {
            outcome.status == ASSEMBLED && packet != null ->
                context.respond(store(context, outcome, packet).toResponse())

            outcome.status == UNCHANGED -> context.cachedOrEmpty(outcome.notes.firstOrNull())

            else -> {
                // The AI service looked at the *current* corpus and could not ground a packet, so
                // anything cached describes a corpus that is gone.
                taskOrientationPacketRepository.deleteByTaskProposalIdAndProjectId(
                    context.proposalId,
                    context.projectId,
                )
                context.respond(null, outcome.notes.firstOrNull() ?: NOT_ASSEMBLED)
            }
        }
    }

    private fun store(
        context: TaskContext,
        outcome: OrientationOutcome,
        schema: OrientationPacketSchema,
    ): TaskOrientationPacket {
        // Replaced wholesale rather than merged: a packet is disposable and holds no human edits, so
        // there is nothing a merge would protect and plenty it could leave inconsistent.
        taskOrientationPacketRepository
            .findByTaskProposalIdAndProjectId(context.proposalId, context.projectId)
            ?.let { taskOrientationPacketRepository.delete(it) }
        taskOrientationPacketRepository.flush()

        val packet = TaskOrientationPacket(
            taskProposalId = context.proposalId,
            projectId = context.projectId,
            taskTitle = schema.taskTitle.ifBlank { context.title },
            summary = schema.summary.takeIf { it.isNotBlank() },
            corpusFingerprint = outcome.provenance?.corpusFingerprint,
            model = outcome.provenance?.model,
            assembledAt = clock.instant(),
        )

        schema.sections
            .mapNotNull { section -> section.step.toStepOrNull()?.let { it to section } }
            .forEachIndexed { index, (step, section) ->
                packet.sections.add(
                    TaskOrientationSection(
                        packet = packet,
                        step = step,
                        title = section.title,
                        body = section.body,
                        position = index,
                    ).apply {
                        section.citations.forEachIndexed { citationIndex, citation ->
                            citations.add(
                                TaskOrientationCitation(
                                    section = this,
                                    filename = citation.filename,
                                    chunkId = citation.chunkId,
                                    sourceUrl = citation.sourceUrl,
                                    position = citationIndex,
                                ),
                            )
                        }
                    },
                )
            }

        schema.sources.forEachIndexed { index, source ->
            packet.sources.add(
                TaskOrientationSource(
                    packet = packet,
                    filename = source.filename,
                    sourceUrl = source.sourceUrl,
                    artifactType = source.artifactType,
                    position = index,
                ),
            )
        }

        return taskOrientationPacketRepository.save(packet)
    }

    /** An unrecognised step is dropped rather than stored: the enum is the contract, not a hint. */
    private fun String.toStepOrNull(): OrientationStep? =
        OrientationStep.entries.firstOrNull { it.name == trim().uppercase() }

    private fun loadContext(hireId: UUID, projectId: UUID): TaskContext? {
        requireMember(hireId, projectId)

        val assignment = taskZeroAssignmentRepository.findByHireIdAndProjectId(hireId, projectId) ?: return null
        val proposal = starterWorkTaskProposalRepository.findById(assignment.proposalId).orElse(null) ?: return null

        // The task's own words, not the one-line summary mining wrote about it -- when the issue it
        // was mined from is still ingested.
        val source = artifactIngestionApi.getTaskSource(proposal.sourceId)
        val cached = taskOrientationPacketRepository.findByTaskProposalIdAndProjectId(proposal.id, projectId)

        return TaskContext(
            proposalId = proposal.id,
            projectId = projectId,
            title = source?.title?.takeIf { it.isNotBlank() } ?: proposal.title,
            body = source?.body?.takeIf { it.isNotBlank() } ?: proposal.summary.orEmpty(),
            labels = source?.labels.orEmpty(),
            taskUrl = proposal.sourceUrl ?: source?.sourceUrl,
            cachedFingerprint = cached?.corpusFingerprint,
            cachedPacket = cached?.toResponse(),
        )
    }

    private fun requireMember(hireId: UUID, projectId: UUID) {
        projectMembershipApi.getProjectMembers(projectId).firstOrNull { it.userId == hireId }
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "User $hireId is not a member of project $projectId",
            )
    }

    private data class TaskContext(
        val proposalId: UUID,
        val projectId: UUID,
        val title: String,
        val body: String,
        val labels: List<String>,
        val taskUrl: String?,
        val cachedFingerprint: String?,
        val cachedPacket: OrientationPacketResponse?,
    ) {
        fun respond(packet: OrientationPacketResponse?, reason: String? = null) =
            MyOrientationResponse(
                taskId = proposalId,
                taskTitle = title,
                taskUrl = taskUrl,
                packet = packet,
                reason = reason,
            )

        /**
         * The cached packet when there is one, otherwise the honest empty state with [reason]. The
         * reason is dropped when a packet is served, so a served packet never carries an apology.
         */
        fun cachedOrEmpty(reason: String?) = respond(cachedPacket, reason.takeIf { cachedPacket == null })
    }

    private companion object {
        const val ASSEMBLED = "assembled"
        const val UNCHANGED = "unchanged"
        const val NOT_ASSEMBLED = "No orientation could be assembled from this project's material"

        /** No current task is not an error: there is simply nothing to orient somebody for yet. */
        val NO_TASK = MyOrientationResponse(
            taskId = null,
            taskTitle = null,
            taskUrl = null,
            packet = null,
            reason = null,
        )
    }
}
