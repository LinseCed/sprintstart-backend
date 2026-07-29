package com.sprintstart.sprintstartbackend.onboarding.external.enums

/**
 * The category of a competency: one durable thing a hire can be at some level of proficiency in.
 *
 * Five kinds are gone, and it is worth saying why rather than leaving the gap unexplained.
 * `CONTRIBUTION` existed so an approved starter task became a graph node that prerequisites could
 * lead to; with no graph to lead through it was a competency nobody could ever be assessed on, so a
 * claimed goal points at the task itself. `POLICY` was created only by the dev seeder, and
 * `CONNECTION`, `CULTURE` and `CHECKPOINT` were created by nothing, anywhere — they described the
 * original design's "learning, culture and connection nodes are the just-in-time path", which was
 * never built. An enum value nothing can produce makes the model look more complete than it is.
 */
enum class CompetencyKind {
    /** A tool, language or technology. */
    SKILL,

    /** A domain or architecture idea specific to this codebase. */
    CONCEPT,
}
