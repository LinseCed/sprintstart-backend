package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ModuleStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.NodeState
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyToolCallDto
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyToolSpecDto
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyModule
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.PathEdge
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.PathNode
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.PathView
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyModuleRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.VerificationRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The buddy's plan-and-material tools: what the hire should learn next, and the content to teach
 * it from.
 *
 * The competency graph stopped being a hire-facing UI and became the buddy's working memory:
 * [GET_LEARNING_PLAN] reads the same projection `GET /me/path` serves (baseline ∪ goal, ordered
 * by prerequisite edges, state derived from the ledger) and reads it *to* the hire with reasons —
 * never scores. [GET_MODULE] hands the buddy a published competency module's pages, with their
 * citations, so teaching stays grounded in the PM-approved material rather than improvised. Both
 * are executed strictly on behalf of the resolved caller, like every buddy tool.
 */
@Component
class BuddyPlanTools(
    private val competencyPathService: CompetencyPathService,
    private val competencyModuleRepository: CompetencyModuleRepository,
    private val verificationRepository: VerificationRepository,
    private val userApi: UserApi,
) {
    /** The tool specs this component owns, aggregated into the buddy's catalog by the executor. */
    fun toolSpecs(): List<BuddyToolSpecDto> = listOf(GET_LEARNING_PLAN_SPEC, GET_MODULE_SPEC)

    /** Whether [toolName] is one of this component's tools. */
    fun handles(toolName: String): Boolean = toolName == GET_LEARNING_PLAN || toolName == GET_MODULE

    /** Executes [call] on behalf of [userId], returning a plain-text result for the model. */
    fun execute(call: BuddyToolCallDto, userId: UUID): String =
        when (call.name) {
            GET_LEARNING_PLAN -> getLearningPlan(userId)
            GET_MODULE -> getModule(userId, call.stringArg("competency_key"))
            else -> "Unknown tool: ${call.name}."
        }

    private fun getLearningPlan(userId: UUID): String {
        val projects = userApi
            .getUsersByIds(listOf(userId))
            .firstOrNull()
            ?.projects
            .orEmpty()
        if (projects.isEmpty()) {
            return "You are not a member of any project yet, so there is no learning plan."
        }
        return projects
            .map { project ->
                val path = competencyPathService.getPathForUser(userId, project.projectId)
                val moduleTitleById = competencyModuleRepository
                    .findAllByProjectIdAndStatus(project.projectId, ModuleStatus.ACTIVE)
                    .associate { it.id to it.title }
                describePlan(project.name, path, moduleTitleById)
            }.joinToString("\n\n")
    }

    private fun describePlan(
        projectName: String,
        path: PathView,
        moduleTitleById: Map<UUID, String>,
    ): String {
        if (path.nodes.isEmpty()) {
            return "On $projectName there is no learning plan yet — the project's baseline hasn't " +
                "been approved and no goal has been claimed. That is a PM's task, not something " +
                "the hire failed to do."
        }

        val labelByKey = path.nodes.associate { it.key to it.label }
        val mastered = path.nodes.filter { it.state == NodeState.MASTERED }
        val available = path.nodes.filter { it.state == NodeState.AVAILABLE }

        return buildString {
            appendLine("Learning plan on $projectName:")
            val goal = path.goal
            if (goal != null) {
                appendLine(
                    "- Working toward: ${goal.label} — " +
                        if (goal.isReachable) {
                            "every prerequisite is met, it can be started now."
                        } else {
                            "${goal.remainingCount} prerequisite(s) still to go."
                        },
                )
            } else {
                appendLine("- Working toward: the team's baseline (no personal goal claimed yet).")
            }

            appendLine("Next up (in the order the graph suggests):")
            available.take(NEXT_UP_COUNT).forEach { node ->
                appendLine("- ${describeNode(node, path.edges, labelByKey, moduleTitleById)}")
            }
            val later = available.drop(NEXT_UP_COUNT)
            if (later.isNotEmpty()) {
                appendLine("After that:")
                later.forEach { node ->
                    appendLine("- ${describeNode(node, path.edges, labelByKey, moduleTitleById)}")
                }
            }

            if (mastered.isNotEmpty()) {
                appendLine("Already met: ${mastered.joinToString(", ") { it.label }}.")
            }
        }.trim()
    }

    /**
     * One plan line: the node, how far along it is, and *why it sits where it sits*. The reason
     * comes from the prerequisite edges, stated as an ordering hint — edges rank, they never lock
     * (the #74 rule: a hire hears reasons, never a score).
     */
    private fun describeNode(
        node: PathNode,
        edges: List<PathEdge>,
        labelByKey: Map<String, String>,
        moduleTitleById: Map<UUID, String>,
    ): String {
        val prerequisites = edges
            .filter { it.to == node.key }
            .mapNotNull { labelByKey[it.from] }
        val reason = if (prerequisites.isEmpty()) {
            "a good place to start"
        } else {
            "usually comes after ${prerequisites.joinToString(", ")}"
        }
        val module = node.moduleId
            ?.let { moduleTitleById[it] }
            ?.let { " Module: “$it” — offer to teach it." }
            ?: " No published module yet — teach from the docs instead."
        return "${node.label} (level ${node.level ?: 0}/${node.targetLevel}) — $reason.$module"
    }

    private fun getModule(userId: UUID, competencyKey: String): String {
        if (competencyKey.isBlank()) {
            return "No competency_key was provided. Ask the plan which competency to teach first."
        }
        val projects = userApi
            .getUsersByIds(listOf(userId))
            .firstOrNull()
            ?.projects
            .orEmpty()
        if (projects.isEmpty()) {
            return "The hire is not a member of any project yet, so there is no module to teach."
        }
        val described = projects.mapNotNull { project ->
            competencyModuleRepository
                .findByCompetencyKeyAndProjectIdAndStatus(competencyKey, project.projectId, ModuleStatus.ACTIVE)
                ?.let { module -> describeModule(project.name, module) }
        }
        return described
            .ifEmpty {
                listOf(
                    "No published module teaches '$competencyKey' on the hire's project(s). Say so " +
                        "plainly, teach from the docs with search_docs instead, and never invent " +
                        "module content.",
                )
            }.joinToString("\n\n")
    }

    /**
     * The module as teaching material: pages in order, each with its citations, plus what the
     * check *asks* (never the rubric or expected answer — that is what the hire is graded
     * against, so it is not the buddy's to reveal).
     */
    private fun describeModule(projectName: String, module: CompetencyModule): String = buildString {
        appendLine("Module “${module.title}” (project: $projectName, id: ${module.id})")
        module.summary?.let { appendLine(it) }

        val check = verificationRepository.findByModuleId(module.id)
        if (check != null) {
            appendLine("Check to pass (${check.type}): “${check.prompt}”")
            appendLine("When the hire has done the work, offer to submit their answer with submit_verification.")
        } else {
            appendLine("No check is configured for this module — it teaches, it does not gate.")
        }

        appendLine("Pages:")
        module.pages.forEachIndexed { index, page ->
            appendLine("${index + 1}. [${page.kind}] ${page.title}")
            page.body?.takeIf { it.isNotBlank() }?.let { body ->
                appendLine(body.take(MAX_PAGE_BODY_CHARS) + if (body.length > MAX_PAGE_BODY_CHARS) " …" else "")
            }
            if (page.citations.isNotEmpty()) {
                val sources = page.citations.joinToString("; ") { citation ->
                    citation.filename + (citation.sourceUrl?.let { " ($it)" } ?: "")
                }
                appendLine("   Sources: $sources")
            }
        }
    }.trim()

    /** Reads a string argument the model passed to a tool, or "" when it is missing/non-text. */
    private fun BuddyToolCallDto.stringArg(name: String): String =
        (arguments[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private companion object {
        const val GET_LEARNING_PLAN = "get_learning_plan"
        const val GET_MODULE = "get_module"

        // How many available nodes are framed as "next up" before the rest become "after that" --
        // enough to choose from, few enough to stay a plan rather than a backlog.
        const val NEXT_UP_COUNT = 3

        // Page bodies are capped so one tool result can't crowd out the whole prompt; the buddy
        // teaches, it does not need to quote the page verbatim end to end.
        const val MAX_PAGE_BODY_CHARS = 1500

        val GET_LEARNING_PLAN_SPEC = BuddyToolSpecDto(
            name = GET_LEARNING_PLAN,
            description = "The hire's learning plan on their project(s): what they are working " +
                "toward (their claimed goal or the team's baseline), which competencies are next " +
                "in the order the graph suggests, how far along each is against its target level, " +
                "and whether a module teaches it. Consult this BEFORE recommending what to learn " +
                "or work on — the plan determines sequence, never your own intuition. State the " +
                "reasons it gives ('usually comes after X'); never invent an order or mention " +
                "scores. Takes no arguments — it always reads the caller.",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { })
            },
        )

        val GET_MODULE_SPEC = BuddyToolSpecDto(
            name = GET_MODULE,
            description = "The published module that teaches one competency: its ordered pages " +
                "with their cited sources, and what its check asks. Use this to teach a " +
                "competency from the shared, PM-approved material, and cite the sources it gives. " +
                "If it answers that no module exists, teach from the docs instead — never " +
                "fabricate module content.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("competency_key") {
                        put("type", "string")
                        put(
                            "description",
                            "The stable key of the competency to teach, as the learning plan names it.",
                        )
                    }
                }
                putJsonArray("required") { add("competency_key") }
            },
        )
    }
}
