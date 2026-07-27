package com.sprintstart.sprintstartbackend.onboarding.service.evidence

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ContributionEvidenceKind
import com.sprintstart.sprintstartbackend.onboarding.service.Contribution
import com.sprintstart.sprintstartbackend.user.external.ProjectMember
import java.util.UUID

/**
 * One source of evidence that a hire completed real work.
 *
 * ### Why this interface exists now and not earlier
 *
 * P0 deliberately shipped `ContributionService` with a single hardcoded source and no abstraction,
 * on the grounds that an interface shaped around one example is reliably the wrong interface. This
 * is the slice with a second implementation, so the shape can be taken from what actually differs
 * between them rather than from a guess.
 *
 * What differed turned out to be less than expected: both answer "what has this person completed
 * here", both key off the resolved [ProjectMember] rather than a bare id, and both return the same
 * four moments. What differs is only *where they look* and *how strong the result is* — which is
 * why [kind] is the only thing an implementation declares beyond the read itself.
 *
 * ### Every provider runs for every hire
 *
 * Providers are **not** filtered by the hire's track. A track's admitted evidence kinds govern
 * which buddy tools mount and what a PM is warned about; they must not govern what counts, or a PM
 * who also ships code would lose credit for their pull requests and a developer who runs the retro
 * would lose credit for that. A track is a bundle of defaults, not a cage — the ramp counts
 * accepted work whatever produced it.
 */
interface EvidenceProvider {
    /** What this provider produces, and therefore how strong its contributions are. */
    val kind: ContributionEvidenceKind

    /**
     * Everything [member] has contributed to this project through this source.
     *
     * @param member The hire, already resolved against the project.
     * @param projectId The project to look in.
     * @return Their contributions from this source, empty when there are none or when this source
     * has nothing to attribute them by.
     */
    fun contributionsFor(member: ProjectMember, projectId: UUID): List<Contribution>
}
