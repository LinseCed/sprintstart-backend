package com.sprintstart.sprintstartbackend.onboarding.external.enums

/**
 * What kind of evidence a contribution rests on, and therefore where it came from.
 *
 * This is what makes [Rigor] legible: a contribution is [Rigor.OBSERVED] *because* it is a merged
 * pull request and [Rigor.ATTESTED] *because* a named person confirmed it, rather than by
 * assertion. It is also the discriminator each new source is added to rather than around.
 */
enum class ContributionEvidenceKind {
    /** A pull request the hire authored, seen through ingestion. Accepted means merged. */
    PULL_REQUEST,

    /**
     * Work a named accountable person confirmed happened and met the bar.
     *
     * Weaker than [PULL_REQUEST] and honestly labelled so: nothing observed it, a person vouched
     * for it. It exists because most roles produce nothing any connected system can see, and the
     * alternative to a person vouching is those roles never finishing onboarding at all.
     */
    ATTESTATION,
}
