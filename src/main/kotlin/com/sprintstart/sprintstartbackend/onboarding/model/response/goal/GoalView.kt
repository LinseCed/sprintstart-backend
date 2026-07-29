package com.sprintstart.sprintstartbackend.onboarding.model.response.goal

import java.util.UUID

/**
 * The starter-work task a hire has committed to on a project.
 *
 * A goal used to be a `CompetencyKind.CONTRIBUTION` node minted when a PM approved a starter task,
 * so that "the task becomes a reachable graph node once a hire has the prerequisite skills". With
 * the graph's structure retired there are no prerequisites to be reachable past, and a contribution
 * node was a competency nobody could ever be assessed on — so the goal points at the task itself.
 *
 * [title] and [summary] therefore come from the proposal rather than from a node's label, which is
 * also the more honest source: it is the wording of the work, not of a synthetic skill.
 */
data class GoalView(
    val proposalId: UUID,
    val title: String,
    val summary: String?,
    val sourceUrl: String?,
)
