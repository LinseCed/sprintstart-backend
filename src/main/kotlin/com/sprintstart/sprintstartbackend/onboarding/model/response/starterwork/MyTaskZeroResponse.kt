package com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork

import java.time.Instant

/**
 * A hire's Task 0 on one project, as they see it on their first-week surface.
 *
 * Every combination is a real, handled state — none is an error:
 * - not [ready] → the environment isn't up yet, so there is no first task to hand out;
 * - [ready] with a [task] → the auto-assigned first task, with the PR loop to walk;
 * - [ready] with [noneAvailable] and no task → the environment is up but no PM has flagged a Task 0
 *   yet. A hire whose first day would otherwise end with "pick something" instead sees "nothing to
 *   assign right now", which is a gap for the PM to fill, not a failure of the hire.
 *
 * [loopProven] is derived — true once the hire has merged any pull request — and writes nothing to
 * the competency ledger: Task 0 proves the loop, not a competency.
 */
data class MyTaskZeroResponse(
    val ready: Boolean,
    val task: StarterWorkTaskProposalResponse?,
    val assignedAt: Instant?,
    val noneAvailable: Boolean,
    val loopProven: Boolean,
)
