package com.sprintstart.sprintstartbackend.onboarding.external.event

import com.sprintstart.sprintstartbackend.onboarding.external.enums.GoalClaimant
import com.sprintstart.sprintstartbackend.shared.ids.ProjectId
import com.sprintstart.sprintstartbackend.shared.ids.UserId

/**
 * A hire claimed a starter-work task as their goal in a project, or replaced the one they had.
 *
 * Published inside the claim's transaction, so a listener that writes takes part in it: if the listener
 * fails, the claim is rolled back with it.
 *
 * @property claimedBy The hire by hand, or the buddy behind a confirm the hire pressed.
 */
data class GoalClaimedEvent(
    val userId: UserId,
    val projectId: ProjectId,
    val claimedBy: GoalClaimant,
)
