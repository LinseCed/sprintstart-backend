package com.sprintstart.sprintstartbackend.onboarding.model.response.competency

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencySource
import java.time.Instant

/**
 * One row of the authenticated user's own durable competency ledger, labeled and typed.
 *
 * The self-serve counterpart of the PM-facing
 * [com.sprintstart.sprintstartbackend.onboarding.model.response.dashboard.UserCompetencyStateResponse];
 * it additionally carries [kind] so the client can group a user's skills (e.g. skills vs.
 * contributions) without a second graph fetch. The ledger is global — a proven skill transfers
 * across projects — so this view is not project-scoped.
 */
data class MyCompetencyResponse(
    val competencyKey: String,
    val label: String,
    val kind: CompetencyKind,
    val level: Int,
    val source: CompetencySource,
    val updatedAt: Instant,
)
