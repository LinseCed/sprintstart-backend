package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.Rigor
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyToolCallDto
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyToolSpecDto
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.AttentionSeverity
import com.sprintstart.sprintstartbackend.user.external.ProjectMembershipApi
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The three reads about the team that team mode always mounts: who needs attention, who is on the
 * project, and how one member is getting on.
 *
 * Every read checks that the person it describes belongs to the turn's project. A member id the model
 * passes is a pointer, never an authorisation. Which tools are mounted on a hop is not decided here but
 * by [BuddyTeamTools], which delegates to this class for the reads.
 */
@Component
class TeamReadTools(
    private val projectAttentionService: ProjectAttentionService,
    private val onboardingMetricsService: OnboardingMetricsService,
    private val myCompetencyService: MyCompetencyService,
    private val arrivalStepService: ArrivalStepService,
    private val projectMembershipApi: ProjectMembershipApi,
) {
    /** The definitions of the team reads, in the order the model is shown them. */
    fun toolSpecs(): List<BuddyToolSpecDto> = listOf(
        GET_TEAM_ATTENTION_SPEC,
        FIND_MEMBER_SPEC,
        GET_MEMBER_PROGRESS_SPEC,
    )

    /**
     * Runs [call] if it is one of the team reads.
     *
     * @return A plain-text result for the model, or `null` when [call] is not a team read.
     */
    fun execute(call: BuddyToolCallDto, context: TeamToolContext): String? =
        when (call.name) {
            GET_TEAM_ATTENTION -> teamAttention(context.projectId)
            FIND_MEMBER -> findMember(context.projectId, call.textArgument("query"))
            GET_MEMBER_PROGRESS -> memberProgress(context.projectId, call.textArgument("member_id"))
            else -> null
        }

    /**
     * A plain-text snapshot of the project's team for a team-mode greeting to ground itself in.
     *
     * Reuses [teamAttention] exactly, so the greeting and the tool can never describe different states.
     */
    fun teamSnapshot(projectId: UUID): String = "Who needs attention:\n" + teamAttention(projectId)

    /**
     * Who on the project is waiting or has stalled, most pressing first.
     *
     * Each line keeps the reason [ProjectAttentionService] wrote and says whose move it is. A pull
     * request waiting on review is the reviewer's move, and a manager told otherwise chases the one
     * person who cannot fix it.
     */
    private fun teamAttention(projectId: UUID): String {
        val attention = projectAttentionService.getAttention(projectId)
        if (attention.items.isEmpty()) {
            return "Nobody on this project (${attention.memberCount} members) is waiting on a review or has " +
                "stalled right now."
        }
        return buildString {
            appendLine("On this project (${attention.memberCount} members), most pressing first:")
            attention.items.forEach { item ->
                appendLine(
                    "- ${item.hireName} [member_id: ${item.hireId}]: ${item.reason} (${item.severity.whoseMove()})",
                )
            }
        }.trim()
    }

    private fun AttentionSeverity.whoseMove(): String =
        when (this) {
            AttentionSeverity.BLOCKED -> "waiting on somebody else's move, not theirs"
            AttentionSeverity.DRIFTING -> "no progress we can see yet"
        }

    /** The project's members matching [query] by name or account, or everyone when it is blank. */
    private fun findMember(projectId: UUID, query: String): String {
        val members = projectMembershipApi.getProjectMembers(projectId)
        if (members.isEmpty()) {
            return "Nobody is on this project yet."
        }
        val needle = query.trim()
        val matches = if (needle.isBlank()) {
            members
        } else {
            members.filter { member ->
                member.displayName.contains(needle, ignoreCase = true) ||
                    member.githubLogin?.contains(needle, ignoreCase = true) == true ||
                    member.jiraDisplayName?.contains(needle, ignoreCase = true) == true
            }
        }
        if (matches.isEmpty()) {
            return "Nobody on this project matches “$needle”. Call find_member without a query to list everyone."
        }
        return buildString {
            appendLine(if (needle.isBlank()) "Everyone on this project:" else "On this project, matching “$needle”:")
            matches.forEach { member ->
                append("- ${member.displayName} [member_id: ${member.userId}]")
                member.githubLogin?.let { append(" (GitHub: $it)") }
                appendLine()
            }
        }.trim()
    }

    /**
     * One member's onboarding on this project: arrival, contributions, competencies.
     *
     * Refuses anybody who is not a member of the project — the check that makes a model-supplied id
     * safe to accept at all.
     */
    private fun memberProgress(projectId: UUID, rawMemberId: String): String {
        val memberId = runCatching { UUID.fromString(rawMemberId.trim()) }.getOrNull()
            ?: return "No valid member_id was given. Call find_member first and pass the member_id it returns."
        val member = projectMembershipApi.getProjectMembers(projectId).firstOrNull { it.userId == memberId }
            ?: return "That person is not on this project, so their progress cannot be read here."

        return listOf(
            "Member: ${member.displayName}",
            "Before they can work:\n" + arrivalFor(memberId, projectId),
            "Contributions on this project:\n" + contributionsFor(memberId, projectId),
            "Competencies:\n" + competenciesFor(memberId),
        ).joinToString("\n\n")
    }

    /**
     * The member's arrival steps that apply on this project, outstanding and settled.
     *
     * Read with [ArrivalStepService.forHireOn], never by filtering the member's full list: that list
     * lets another of their projects override a company step, and narrowing it afterwards would hide
     * a step that still applies here. Same two refusals as the hire's own tool: no total, and nothing
     * described as blocking.
     */
    private fun arrivalFor(memberId: UUID, projectId: UUID): String {
        val steps = arrivalStepService.forHireOn(memberId, projectId)
        if (steps.isEmpty()) {
            return "No arrival steps apply to them on this project."
        }
        val (settled, outstanding) = steps.partition { it.settled }
        return buildString {
            if (outstanding.isEmpty()) {
                appendLine("Nothing is outstanding.")
            } else {
                appendLine("Still outstanding (none of this stops them working):")
                outstanding.forEach { resolved ->
                    append("- ${resolved.step.title}")
                    if (!resolved.step.selfConfirmable) {
                        append(" [confirmed by the system; they cannot mark it done themselves]")
                    }
                    appendLine()
                }
            }
            if (settled.isNotEmpty()) {
                appendLine("Already settled:")
                settled.forEach { resolved ->
                    val how = if (resolved.rigor == Rigor.OBSERVED) "we confirmed this" else "they told us"
                    appendLine("- ${resolved.step.title} ($how)")
                }
            }
        }.trim()
    }

    private fun contributionsFor(memberId: UUID, projectId: UUID): String {
        val timeline = onboardingMetricsService.getHireTimeline(memberId, projectId)
            ?: return "No onboarding metrics exist for them on this project yet."
        return buildString {
            appendLine("- Open pull requests: ${timeline.openContributionCount}")
            appendLine("- Merged pull requests: ${timeline.acceptedContributionCount}")
            timeline.longestOpenWaitHours?.let {
                appendLine("- Longest pull request currently waiting on a reviewer: $it hours")
            }
            timeline.hoursToFirstResponse?.let {
                appendLine("- Time from their first pull request to its first response: $it hours")
            }
            val stall = if (timeline.stalled) "yes" + (timeline.stalledReason?.let { " ($it)" } ?: "") else "no"
            appendLine("- Stalled: $stall")
            appendLine("- Pull requests sent back for changes: ${timeline.returnedContributionCount}")
        }.trim()
    }

    /** Level-0 rows are placed-but-unknown, not evidence, and are excluded as on the hire's own tool. */
    private fun competenciesFor(memberId: UUID): String {
        val ledger = myCompetencyService.getCompetenciesForUser(memberId).filter { it.level > 0 }
        if (ledger.isEmpty()) {
            return "Nothing demonstrated on their competency ledger yet — normal early on."
        }
        val (held, inProgress) = ledger.partition { it.level >= it.targetLevel }
        return buildString {
            appendLine("Meeting their target level: ${held.size}")
            held.forEach { appendLine("- ${it.label} (level ${it.level}/${it.targetLevel})") }
            if (inProgress.isNotEmpty()) {
                appendLine("Progress made, target not yet met: ${inProgress.size}")
                inProgress.forEach { appendLine("- ${it.label} (level ${it.level}/${it.targetLevel})") }
            }
        }.trim()
    }

    companion object {
        const val GET_TEAM_ATTENTION = "get_team_attention"
        const val FIND_MEMBER = "find_member"
        const val GET_MEMBER_PROGRESS = "get_member_progress"

        val GET_TEAM_ATTENTION_SPEC = BuddyToolSpecDto(
            name = GET_TEAM_ATTENTION,
            description = "Who on this project is waiting on somebody else or has stalled, most pressing " +
                "first, each with the reason and a member_id. Use it for 'who needs my attention?' or " +
                "'is anyone stuck?'. Describe each person's situation as facts, never as a judgment of the " +
                "person: a pull request waiting on review is the reviewer's move. Takes no arguments — it " +
                "always reads the project this conversation is about.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { })
            },
        )

        val FIND_MEMBER_SPEC = BuddyToolSpecDto(
            name = FIND_MEMBER,
            description = "The people on this project matching a name or account, each with their member_id. " +
                "Call it before get_member_progress whenever the manager names somebody, and pass the " +
                "member_id it returns — never guess an id. Without a query it lists everyone on the project.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Part of the person's name, GitHub login or Jira name. Optional.")
                    }
                }
            },
        )

        val GET_MEMBER_PROGRESS_SPEC = BuddyToolSpecDto(
            name = GET_MEMBER_PROGRESS,
            description = "One project member's onboarding on this project: what they still need before they " +
                "can work, their pull requests and whether any are waiting on a reviewer, and their " +
                "competencies. Use it for 'how far along is Sam?'. Pass the member_id from find_member or " +
                "get_team_attention. Report facts; never rank people against each other or call someone slow.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("member_id") {
                        put("type", "string")
                        put("description", "The member_id of the person, from find_member or get_team_attention.")
                    }
                }
                putJsonArray("required") { add("member_id") }
            },
        )
    }
}
