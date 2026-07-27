package com.sprintstart.sprintstartbackend.onboarding.external.enums

/**
 * What kind of evidence a contribution rests on, and therefore where it came from.
 *
 * One value today: everything the ramp can currently see is a pull request the ingestion module
 * already recorded. The enum exists anyway because it is what makes [Rigor] legible — a
 * contribution is [Rigor.OBSERVED] *because* it is a merged pull request, not by assertion — and
 * because it is the discriminator a second source is added to rather than around.
 */
enum class ContributionEvidenceKind {
    /** A pull request the hire authored, seen through ingestion. Accepted means merged. */
    PULL_REQUEST,
}
