package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.ingestion.external.ArtifactIngestionApi
import com.sprintstart.sprintstartbackend.ingestion.external.AuthoredPullRequest
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ContributionEvidenceKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ContributionState
import com.sprintstart.sprintstartbackend.onboarding.external.enums.Rigor
import com.sprintstart.sprintstartbackend.user.external.ProjectMember
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * The real work a hire has completed on a project — whatever form that work takes.
 *
 * ### Why this exists
 *
 * Onboarding is measured by getting somebody to a unit of real work the team accepted, and then to
 * the next one with less help. The *pull request* was never the point; it is one instantiation of
 * that idea, and the only one a code-only corpus can see. Reading pull requests directly, as
 * [RampService] and [OnboardingMetricsService] used to, put "this hire writes code" into the
 * definition of progress — so a Scrum Master or a PM was permanently at stage zero, permanently
 * "no pull request opened", and permanently invisible on the PM dashboard.
 *
 * A [Contribution] is that unit stated generally: authored by the hire, answered by somebody,
 * possibly sent back, eventually accepted or not. Those four moments are the whole measurement
 * surface, and none of them are specific to git.
 *
 * ### Derived, not stored
 *
 * Nothing here is persisted. Every contribution is composed on read from artifacts ingestion
 * already holds, following the same rule [OnboardingMetricsService] documents: a second log of
 * facts that already live somewhere durable would drift from the first and would need a backfill
 * for everything predating it. Deriving means this covers history from the day it ships.
 *
 * The first genuinely non-derivable evidence — a person attesting that work happened — is exactly
 * when a table becomes the honest choice, and it will get one then, for the same reason
 * `BuddyContact` did.
 *
 * ### Where the second source goes
 *
 * There is deliberately **no provider interface yet**. One source exists, and an abstraction shaped
 * around a single example is reliably the wrong abstraction — the second one is what reveals what
 * actually varies. [forHire] is the seam: a second source is another private mapper composed into
 * that list, and the interface gets extracted once there are two implementations to extract it
 * from.
 */
@Service
class ContributionService(
    private val artifactIngestionApi: ArtifactIngestionApi,
) {
    /**
     * Everything [member] has contributed to this project, newest last.
     *
     * Takes the resolved [ProjectMember] rather than a user id because callers have already
     * resolved it and because identity is what a source needs: today the declared GitHub login,
     * tomorrow the user id an attestation is filed against.
     *
     * @param member The hire, already resolved against the project.
     * @param projectId The project to look in.
     * @return Their contributions, empty when there is nothing to attribute.
     */
    fun forHire(member: ProjectMember, projectId: UUID): List<Contribution> {
        return pullRequestContributions(member, projectId)
    }

    /**
     * Pull requests the hire authored, as contributions.
     *
     * A blank GitHub login yields nothing rather than an error: no declared identity means no
     * attribution is possible, which callers already distinguish from "did no work".
     */
    private fun pullRequestContributions(member: ProjectMember, projectId: UUID): List<Contribution> {
        val login = member.githubLogin
        if (login.isNullOrBlank()) {
            return emptyList()
        }
        return artifactIngestionApi
            .getAuthoredPullRequests(projectId, login)
            .map { it.toContribution() }
    }

    private fun AuthoredPullRequest.toContribution(): Contribution {
        return Contribution(
            evidenceRef = artifactId,
            kind = ContributionEvidenceKind.PULL_REQUEST,
            rigor = Rigor.OBSERVED,
            state = stateOf(this),
            openedAt = openedAt,
            firstResponseAt = firstResponseAt,
            acceptedAt = mergedAt,
            returnedCount = changesRequestedCount,
        )
    }

    /**
     * A merged pull request is accepted; a genuinely open one is in flight; anything else was
     * closed without merging.
     *
     * Leans on [AuthoredPullRequest.isOpen] rather than re-deriving openness, so "open" keeps
     * meaning what it means everywhere else — merge state alone would count a closed-unmerged pull
     * request as still waiting.
     */
    private fun stateOf(pullRequest: AuthoredPullRequest): ContributionState {
        return when {
            pullRequest.mergedAt != null -> ContributionState.ACCEPTED
            pullRequest.isOpen -> ContributionState.IN_FLIGHT
            else -> ContributionState.ABANDONED
        }
    }
}

/**
 * One unit of real work a hire produced, reduced to the moments onboarding measures.
 *
 * **Not to be confused with `CompetencyKind.CONTRIBUTION`**, which is a graph node kind: that is
 * the *goal* a hire is working toward, this is the *evidence* that they completed something. The
 * two meet only in that finishing the former produces the latter.
 *
 * [firstResponseAt] null means nobody has answered yet — a finding, not missing data. A
 * [returnedCount] of zero is half the operational definition of autonomy: acceptance alone cannot
 * tell clean work from work sent back three times.
 *
 * **Invariant:** [state] `== ACCEPTED` implies [acceptedAt] is non-null, and vice versa. It is
 * established in the mappers that build these, which are the only way one is constructed.
 */
data class Contribution(
    /** The evidence this rests on: an ingested artifact today, an attestation row later. */
    val evidenceRef: UUID,
    val kind: ContributionEvidenceKind,
    val rigor: Rigor,
    val state: ContributionState,
    val openedAt: Instant?,
    val firstResponseAt: Instant?,
    val acceptedAt: Instant?,
    val returnedCount: Int,
) {
    /** Accepted through the team's normal quality bar. The unit the ramp counts. */
    val isAccepted: Boolean
        get() = state == ContributionState.ACCEPTED

    /** Submitted and still waiting on somebody else. */
    val isInFlight: Boolean
        get() = state == ContributionState.IN_FLIGHT
}
