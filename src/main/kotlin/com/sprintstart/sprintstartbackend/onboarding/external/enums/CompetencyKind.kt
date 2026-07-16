package com.sprintstart.sprintstartbackend.onboarding.external.enums

/**
 * The category of a competency node in the onboarding competency graph.
 *
 * A competency represents one durable thing a hire can be at some level of proficiency in.
 * The kind determines how the node is treated during placement, path projection, and
 * verification. The graph terminates in [CONTRIBUTION] nodes; the other kinds are the
 * just-in-time path to get there.
 */
enum class CompetencyKind {
    SKILL,
    CONCEPT,
    CONTRIBUTION,
    POLICY,
    CONNECTION,
    CULTURE,
    CHECKPOINT,
}
