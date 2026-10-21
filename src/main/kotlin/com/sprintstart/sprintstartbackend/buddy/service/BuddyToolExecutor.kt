package com.sprintstart.sprintstartbackend.buddy.service

import com.sprintstart.sprintstartbackend.assistant.external.BuddyToolCallDto
import com.sprintstart.sprintstartbackend.assistant.external.BuddyToolSpecDto
import com.sprintstart.sprintstartbackend.assistant.external.HireTool
import com.sprintstart.sprintstartbackend.assistant.external.HireToolContext
import com.sprintstart.sprintstartbackend.shared.ids.UserId
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Runs the backend-owned tools the buddy agent may call, and describes them to the AI reasoner.
 *
 * The buddy answers corpus questions AI-side (``search_docs``); tools here answer questions about
 * the hire's *own* state -- their onboarding path, their setup, their work -- which only the backend
 * can see. Each tool is executed strictly on behalf of the resolved caller — the agent never
 * supplies whose data to read, so one hire can never read another's metrics through the buddy.
 *
 * Most tools come from the features as [HireTool] beans, each mounted on its own condition and in its own
 * order. The path's and the board's tools are still the Buddy's own.
 */
@Component
class BuddyToolExecutor(
    private val buddyBoardTools: BuddyBoardTools,
    private val hireTools: List<HireTool>,
    private val buddyPathTools: BuddyPathTools,
) {
    /**
     * The backend tools the AI reasoner is told it may call, for this hire.
     *
     * Mounted per hire, not globally, wherever a tool has a subject that may not exist: a tool
     * that can only ever answer "there is nothing" invites the mentor to raise it anyway.
     *
     * The gate is always the subject's own emptiness, asked of the service that owns it — an
     * arrival list nobody authored, a hire with nothing left to place. Never the hire's role: every
     * tool that has something to say is offered to everybody, and what a hire is asked about is
     * decided by what is actually there for them.
     *
     * That rule covers the caller's own buddy. Team mode ([BuddyTeamTools]) is the one exception, and
     * a deliberate one: a project's manager is handed tools that read *other people* on that project.
     * It is gated on managing the project — checked by [BuddyTeamService] before every turn — and every
     * team tool re-checks that the person it reads is on that project. None of these tools is ever
     * mounted here.
     */
    fun toolSpecs(userId: UUID): List<BuddyToolSpecDto> = buildList {
        // First: the path *is* the onboarding, and the buddy is the tutor along it. A mentor that has
        // not read it answers "what should I do next" out of the work pool while the hire is looking
        // at a page that says something else. Mounted only when a path exists.
        addAll(buddyPathTools.toolSpecs(userId))
        hireTools.filter { it.isMountedFor(UserId(userId)) }.forEach { add(it.spec) }
        addAll(buddyBoardTools.toolSpecs())
    }

    /**
     * A plain-text snapshot of the hire's own state, for the buddy's opening greeting to ground
     * itself in. Reuses the exact reads the caller-scoped tools expose, so the opener and the tools
     * can never describe different states.
     *
     * The path comes first, and the order is the feature: the onboarding is what a greeting opens
     * on. Setup comes next, ahead of how their work is going -- a greeting grounded in progress
     * before setup greets a hire who cannot get in with a good first issue. Each part is omitted
     * entirely when it has nothing: a greeting grounded in "arrival: nothing" will find something to
     * say about it.
     */
    fun stateSnapshot(userId: UUID): String =
        buildList {
            // Absent entirely for a hire with no path, so a greeting can never open by discussing
            // one they have not got.
            add(buddyPathTools.snapshotFor(userId))
            addAll(hireTools.sortedBy { it.snapshotPosition }.map { it.snapshotFor(UserId(userId)) })
        }.filterNotNull().joinToString("\n\n")

    /**
     * Executes [call] on behalf of [userId], returning a plain-text result for the model.
     *
     * @param currentPage The app path the caller was on when they spoke, for the app guide.
     */
    fun execute(call: BuddyToolCallDto, userId: UUID, currentPage: String? = null): String {
        hireTools
            .firstOrNull { it.spec.name == call.name }
            ?.let { return it.execute(call, HireToolContext(UserId(userId), currentPage)) }
        return when {
            buddyBoardTools.handles(call.name) -> buddyBoardTools.execute(call, userId)
            buddyPathTools.handles(call.name) -> buddyPathTools.execute(userId)
            else -> "Unknown tool: ${call.name}."
        }
    }
}
