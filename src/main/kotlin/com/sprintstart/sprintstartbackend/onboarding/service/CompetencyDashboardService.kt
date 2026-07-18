package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencySource
import com.sprintstart.sprintstartbackend.onboarding.model.response.dashboard.CompetencyAggregateResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.dashboard.CompetencySourceCounts
import com.sprintstart.sprintstartbackend.onboarding.model.response.dashboard.UserCompetencyStateResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.dashboard.UserCompetencySummaryResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserCompetencyStateRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * PM-facing team-wide competency signal, sourced from the durable ledger
 * ([com.sprintstart.sprintstartbackend.onboarding.model.entity.UserCompetencyState]) rather than
 * step completion.
 *
 * The existing `GET /team-overview` (see
 * [OnboardingPathService][com.sprintstart.sprintstartbackend.onboarding.service.OnboardingPathService])
 * reports `progressPercentage` from `StepStatus.FINISHED`/`SKIPPED`, which conflates a real
 * verified check with an unconfigured step a hire simply marked done -- issue #9 explicitly asks
 * for a signal that isn't that. This service is intentionally standalone (not an extension of
 * `TeamOverviewUserDto`) rather than touching that existing, already-consumed response shape.
 */
@Service
class CompetencyDashboardService(
    private val competencyRepository: CompetencyRepository,
    private val userCompetencyStateRepository: UserCompetencyStateRepository,
    private val userApi: UserApi,
) {
    /**
     * Returns, for every live competency, how many users hold it at each level and by which
     * [CompetencySource].
     *
     * A competency with no ledger rows yet still appears, with zero counts -- so a PM sees graph
     * coverage gaps, not just an empty list.
     */
    @Transactional(readOnly = true)
    fun getCompetencyAggregate(): List<CompetencyAggregateResponse> {
        val statesByKey = userCompetencyStateRepository.findAll().groupBy { it.competencyKey }

        return competencyRepository.findAll().map { competency ->
            val engaged = statesByKey[competency.key].orEmpty().filter { it.level > 0 }
            CompetencyAggregateResponse(
                competencyKey = competency.key,
                label = competency.label,
                kind = competency.kind,
                usersEngaged = engaged.size,
                levelCounts = engaged.groupingBy { it.level }.eachCount(),
                sourceCounts = CompetencySourceCounts(
                    assessed = engaged.count { it.source == CompetencySource.ASSESSED },
                    verified = engaged.count { it.source == CompetencySource.VERIFIED },
                    declared = engaged.count { it.source == CompetencySource.DECLARED },
                ),
            )
        }
    }

    /**
     * Returns a paginated, per-user breakdown of each user's full competency ledger.
     *
     * Filtering mirrors [OnboardingPathService.getTeamOverview]'s signature for consistency, but
     * pagination is delegated straight to [UserApi.searchUsers] -- unlike that method, there is no
     * computed-field sort to justify fetching every user up front.
     */
    @Transactional(readOnly = true)
    fun getUserCompetencySummaries(
        search: String?,
        roleIds: List<UUID>?,
        projectIds: List<UUID>?,
        pageable: Pageable,
    ): Page<UserCompetencySummaryResponse> {
        val usersPage = userApi.searchUsers(search, roleIds, projectIds, pageable)
        val users = usersPage.content
        if (users.isEmpty()) {
            return PageImpl(emptyList(), pageable, usersPage.totalElements)
        }

        val statesByUser = userCompetencyStateRepository
            .findAllByUserIdIn(users.map { it.id })
            .groupBy { it.userId }
        val competenciesByKey = competencyRepository.findAll().associateBy { it.key }

        val summaries = users.map { user ->
            UserCompetencySummaryResponse(
                userId = user.id,
                firstname = user.firstname,
                lastname = user.lastname,
                competencies = statesByUser[user.id].orEmpty().mapNotNull { state ->
                    competenciesByKey[state.competencyKey]?.let { competency ->
                        UserCompetencyStateResponse(
                            competencyKey = state.competencyKey,
                            label = competency.label,
                            level = state.level,
                            source = state.source,
                            updatedAt = state.updatedAt,
                        )
                    }
                },
            )
        }
        return PageImpl(summaries, pageable, usersPage.totalElements)
    }
}
