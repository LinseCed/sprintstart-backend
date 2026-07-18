package com.sprintstart.sprintstartbackend.onboarding.model.response.dashboard

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencySource
import java.time.Instant
import java.util.UUID

/**
 * How many users hold a competency by [CompetencySource] -- the whole point of this dashboard
 * over the legacy step-completion team overview: a competency backed by `VERIFIED` (or, once
 * Phase 4's artifact checks land, `ARTIFACT`) evidence is a materially different signal than one
 * that is merely self-`DECLARED`.
 */
data class CompetencySourceCounts(
    val assessed: Int,
    val verified: Int,
    val declared: Int,
)

/**
 * Team-wide ledger signal for one live competency.
 *
 * @property usersEngaged Count of users with a ledger row at [level] > 0 for this competency
 * (i.e. excludes users who have never been placed/assessed on it at all).
 * @property levelCounts Level (1..4) to count of engaged users at that level.
 */
data class CompetencyAggregateResponse(
    val competencyKey: String,
    val label: String,
    val kind: CompetencyKind,
    val usersEngaged: Int,
    val levelCounts: Map<Int, Int>,
    val sourceCounts: CompetencySourceCounts,
)

data class UserCompetencyStateResponse(
    val competencyKey: String,
    val label: String,
    val level: Int,
    val source: CompetencySource,
    val updatedAt: Instant,
)

data class UserCompetencySummaryResponse(
    val userId: UUID,
    val firstname: String,
    val lastname: String,
    val competencies: List<UserCompetencyStateResponse>,
)
