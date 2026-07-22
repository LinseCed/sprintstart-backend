package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyToolCallDto
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyToolSpecDto
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.MyCompetencyResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.HireTimelineResponse
import com.sprintstart.sprintstartbackend.user.external.UserApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Runs the backend-owned tools the buddy agent may call, and describes them to the AI reasoner.
 *
 * The buddy answers corpus questions AI-side (``search_docs``); tools here answer questions about
 * the hire's *own* onboarding, which only the backend can see. Each tool is executed strictly on
 * behalf of the resolved caller — the agent never supplies whose data to read, so one hire can
 * never read another's metrics through the buddy.
 */
@Component
class BuddyToolExecutor(
    private val onboardingMetricsService: OnboardingMetricsService,
    private val myCompetencyService: MyCompetencyService,
    private val userApi: UserApi,
) {
    /** The backend tools the AI reasoner is told it may call. */
    fun toolSpecs(): List<BuddyToolSpecDto> = listOf(GET_MY_METRICS_SPEC, GET_MY_COMPETENCIES_SPEC)

    /** Executes [call] on behalf of [userId], returning a plain-text result for the model. */
    fun execute(call: BuddyToolCallDto, userId: UUID): String =
        when (call.name) {
            GET_MY_METRICS -> getMyMetrics(userId)
            GET_MY_COMPETENCIES -> getMyCompetencies(userId)
            else -> "Unknown tool: ${call.name}."
        }

    private fun getMyMetrics(userId: UUID): String {
        val projects = userApi.getUsersByIds(listOf(userId)).firstOrNull()?.projects.orEmpty()
        if (projects.isEmpty()) {
            return "You are not a member of any project yet, so there are no onboarding metrics."
        }
        val described = projects.mapNotNull { project ->
            onboardingMetricsService.getHireTimeline(userId, project.projectId)
                ?.let { describe(project.name, it) }
        }
        return described.ifEmpty { listOf("No onboarding metrics are available for you yet.") }
            .joinToString("\n\n")
    }

    private fun describe(projectName: String, timeline: HireTimelineResponse): String = buildString {
        appendLine("Project: $projectName")
        appendLine("- Open pull requests: ${timeline.openPullRequestCount}")
        appendLine("- Merged pull requests: ${timeline.mergedPullRequestCount}")
        timeline.longestOpenWaitHours?.let {
            appendLine("- Longest pull request currently waiting on a review: $it hours")
        }
        timeline.hoursToFirstResponse?.let {
            appendLine("- Time from your first pull request to its first response: $it hours")
        }
        val stall = if (timeline.stalled) {
            "yes" + (timeline.stalledReason?.let { " ($it)" } ?: "")
        } else {
            "no"
        }
        appendLine("- Stalled: $stall")
        appendLine("- Pull requests sent back for changes: ${timeline.reworkedPullRequestCount}")
        timeline.autonomyReachedAt?.let { appendLine("- Reached autonomy at: $it") }
    }.trim()

    private fun getMyCompetencies(userId: UUID): String {
        // Level-0 rows are placed-but-unknown, not evidence of a skill — exclude them, matching how
        // the skills rail treats them, so the buddy never reports a competency the hire hasn't shown.
        val ledger = myCompetencyService.getCompetenciesForUser(userId).filter { it.level > 0 }
        if (ledger.isEmpty()) {
            return "You have no demonstrated competencies on your ledger yet — that's normal early on."
        }
        val (held, inProgress) = ledger.partition { it.level >= it.targetLevel }
        return buildString {
            appendLine("Competencies held (meet their target level): ${held.size}")
            held.forEach { appendLine("- ${it.label} (level ${it.level}/${it.targetLevel})") }
            if (inProgress.isNotEmpty()) {
                appendLine("Below target (progress made, not yet met): ${inProgress.size}")
                inProgress.forEach { appendLine("- ${it.label} (level ${it.level}/${it.targetLevel})") }
            }
        }.trim()
    }

    private companion object {
        const val GET_MY_METRICS = "get_my_metrics"
        const val GET_MY_COMPETENCIES = "get_my_competencies"

        // No-argument JSON schema shared by every caller-scoped tool: the agent never says whose
        // data to read, so there is nothing to pass.
        private fun noArgs() = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        }

        val GET_MY_METRICS_SPEC = BuddyToolSpecDto(
            name = GET_MY_METRICS,
            description = "The hire's own onboarding metrics on the project(s) they are onboarding " +
                "on: open and merged pull requests, how long a pull request has been waiting on a " +
                "review, whether they are stalled, review rework, and whether they have reached " +
                "autonomy. Use this for questions about the hire's own progress, e.g. 'is my PR " +
                "stuck?' or 'am I on track?'. Takes no arguments — it always reads the caller.",
            parameters = noArgs(),
        )

        val GET_MY_COMPETENCIES_SPEC = BuddyToolSpecDto(
            name = GET_MY_COMPETENCIES,
            description = "The hire's own competency ledger: which skills they have demonstrated " +
                "(and at what level vs the target), and which they have made progress toward but " +
                "not yet met. Use this for questions about where the hire stands or what they have " +
                "shown, e.g. 'where do I stand?' or 'what have I proven so far?'. Takes no " +
                "arguments — it always reads the caller.",
            parameters = noArgs(),
        )
    }
}
