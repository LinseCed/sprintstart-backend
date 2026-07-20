package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.ingestion.external.ArtifactIngestionApi
import com.sprintstart.sprintstartbackend.onboarding.model.entity.EnvironmentEvidence
import com.sprintstart.sprintstartbackend.onboarding.model.entity.EnvironmentReadiness
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.MyEnvironmentResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.EnvironmentReadinessRepository
import com.sprintstart.sprintstartbackend.user.external.ProjectMember
import com.sprintstart.sprintstartbackend.user.external.ProjectMembershipApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Whether a hire's environment is up, for one project — settled by evidence, never by a checkbox.
 *
 * Two ways readiness is established:
 * - **Reported**: the documented one-liner posts a successful build-and-test run, or a green CI run,
 *   authenticated as the hire. Stored once — readiness does not change, so the first evidence wins.
 * - **Derived**: a hire who has already opened a pull request plainly got the environment working,
 *   whether or not they ran the command. Computed on read from ingested work, never written, so
 *   there is nothing to backfill. (A commit cannot be used: in this system it carries a git author
 *   *name*, not a GitHub account, so it cannot be attributed to a hire; a pull request can.)
 *
 * Not-ready is a real returned state, not an error. A hire is never gated out of the product for
 * failing a setup step — the next action is UI, not a 403.
 */
@Service
class EnvironmentReadinessService(
    private val environmentReadinessRepository: EnvironmentReadinessRepository,
    private val projectMembershipApi: ProjectMembershipApi,
    private val artifactIngestionApi: ArtifactIngestionApi,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Records reported evidence that the environment is up.
     *
     * Idempotent: once a hire is ready they stay ready, so a second report is a no-op that returns
     * the readiness already on record rather than overwriting when or how it was settled.
     *
     * @throws ResponseStatusException 404 when the hire is not a member of the project; 400 for a
     * derived-only evidence type or a `readyAt` in the future.
     */
    @Transactional
    fun report(
        hireId: UUID,
        projectId: UUID,
        evidence: EnvironmentEvidence,
        readyAt: Instant?,
        detail: String?,
    ): MyEnvironmentResponse {
        requireMember(hireId, projectId)

        if (evidence == EnvironmentEvidence.PULL_REQUEST) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "PULL_REQUEST readiness is derived from ingested work, not reported",
            )
        }

        val now = clock.instant()
        val at = readyAt ?: now
        if (at.isAfter(now)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "readyAt cannot be in the future")
        }

        environmentReadinessRepository.findByHireIdAndProjectId(hireId, projectId)?.let {
            return it.toResponse(derived = false)
        }

        val saved = environmentReadinessRepository.save(
            EnvironmentReadiness(
                hireId = hireId,
                projectId = projectId,
                readyAt = at,
                evidence = evidence,
                evidenceDetail = detail,
                recordedAt = now,
            ),
        )
        return saved.toResponse(derived = false)
    }

    /**
     * A hire's readiness: the reported evidence if any, otherwise derived from a pull request they
     * authored, otherwise not-ready.
     *
     * @throws ResponseStatusException 404 when the hire is not a member of the project.
     */
    @Transactional(readOnly = true)
    fun getReadiness(hireId: UUID, projectId: UUID): MyEnvironmentResponse {
        val member = requireMember(hireId, projectId)

        environmentReadinessRepository.findByHireIdAndProjectId(hireId, projectId)?.let {
            return it.toResponse(derived = false)
        }

        val derivedAt = derivedReadyAt(member, projectId)
        return if (derivedAt != null) {
            MyEnvironmentResponse(
                ready = true,
                readyAt = derivedAt,
                evidence = EnvironmentEvidence.PULL_REQUEST,
                evidenceDetail = "A pull request you authored",
                derived = true,
            )
        } else {
            MyEnvironmentResponse(
                ready = false,
                readyAt = null,
                evidence = null,
                evidenceDetail = null,
                derived = false,
            )
        }
    }

    /**
     * When readiness was settled, for the metrics timeline — reported evidence if stored, otherwise
     * the derived pull-request moment, otherwise null. Kept here so the "what counts as ready" rule
     * lives in one place rather than being re-implemented by the metrics service.
     */
    @Transactional(readOnly = true)
    fun readyAtFor(member: ProjectMember, projectId: UUID): Instant? =
        environmentReadinessRepository.findByHireIdAndProjectId(member.userId, projectId)?.readyAt
            ?: derivedReadyAt(member, projectId)

    /** The earliest pull request the hire authored — the derivable proxy for "got the env working". */
    private fun derivedReadyAt(member: ProjectMember, projectId: UUID): Instant? {
        val login = member.githubLogin
        if (login.isNullOrBlank()) return null
        return artifactIngestionApi
            .getAuthoredPullRequests(projectId, login)
            .mapNotNull { it.openedAt }
            .minOrNull()
    }

    private fun requireMember(hireId: UUID, projectId: UUID): ProjectMember =
        projectMembershipApi.getProjectMembers(projectId).firstOrNull { it.userId == hireId }
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "User $hireId is not a member of project $projectId",
            )

    private fun EnvironmentReadiness.toResponse(derived: Boolean) =
        MyEnvironmentResponse(
            ready = true,
            readyAt = readyAt,
            evidence = evidence,
            evidenceDetail = evidenceDetail,
            derived = derived,
        )
}
