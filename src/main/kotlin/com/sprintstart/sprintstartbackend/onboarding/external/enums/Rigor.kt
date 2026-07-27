package com.sprintstart.sprintstartbackend.onboarding.external.enums

/**
 * How strong the evidence behind a completed contribution is.
 *
 * Onboarding's terminal event used to be a merged pull request, which is evidence a hire could not
 * have faked: ingestion observed it. Roles that do not write code — a Scrum Master facilitating a
 * retro, a PM publishing a plan — can rarely be observed that way, and the honest answer is to
 * record *how* something was established rather than to pretend the two are the same measurement.
 *
 * This is the same discipline [CompetencySource] already applies to the ledger
 * ([CompetencySource.VERIFIED] outranks [CompetencySource.ASSESSED]) and [VerificationType] applies
 * to checks, extended to the evidence stream the ramp and the metrics read.
 *
 * Ordered weakest-last so callers can compare: [OBSERVED] is the strongest.
 */
enum class Rigor {
    /** A system recorded it; the hire could not have produced the record themselves. */
    OBSERVED,

    /** A named accountable person, never the hire, confirmed the work happened and met the bar. */
    ATTESTED,

    /** The hire said so, with nothing behind it. Never counts toward autonomy. */
    DECLARED,
}
