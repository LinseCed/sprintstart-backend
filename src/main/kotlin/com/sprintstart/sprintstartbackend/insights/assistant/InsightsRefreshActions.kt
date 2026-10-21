package com.sprintstart.sprintstartbackend.insights.assistant

import com.sprintstart.sprintstartbackend.assistant.external.BuddyProposalRisk
import com.sprintstart.sprintstartbackend.assistant.external.BuddyToolCallDto
import com.sprintstart.sprintstartbackend.assistant.external.BuddyToolSpecDto
import com.sprintstart.sprintstartbackend.assistant.external.TeamActionDraft
import com.sprintstart.sprintstartbackend.assistant.external.TeamActionHandler
import com.sprintstart.sprintstartbackend.assistant.external.TeamArea
import com.sprintstart.sprintstartbackend.assistant.external.TeamToolContext
import com.sprintstart.sprintstartbackend.assistant.external.stringFields
import com.sprintstart.sprintstartbackend.insights.external.InsightsRefreshApi
import kotlinx.serialization.json.JsonObject
import org.springframework.stereotype.Component

/** Offers to rebuild the project's recurring-question groups. */
@Component
class RefreshFaqAction(
    private val insightsRefreshApi: InsightsRefreshApi,
) : TeamActionHandler {
    override val area = TeamArea.KNOWLEDGE
    override val risk = BuddyProposalRisk.BULK
    override val spec = BuddyToolSpecDto(
        name = "refresh_faq",
        description = "Offer to rebuild this project's FAQ: every question asked here regrouped into recurring " +
            "questions. It asks the AI service and takes a while. Offer it when the manager wants the FAQ up to " +
            "date, not as a way to answer a question. Takes no arguments.",
        parameters = stringFields(required = emptyList()),
    )

    override fun draft(call: BuddyToolCallDto, context: TeamToolContext): TeamActionDraft =
        TeamActionDraft.Proposed(
            params = JsonObject(emptyMap()),
            label = "Refresh the FAQ",
            preview = "Rebuild this project's FAQ: every question asked here is regrouped into recurring " +
                "questions, replacing the current groups. It asks the AI service and can take a while.",
        )

    override suspend fun perform(params: JsonObject, context: TeamToolContext): String {
        val groups = insightsRefreshApi.refreshFaq(context.projectId)
        return "Refreshed the FAQ: $groups recurring-question groups."
    }
}

/** Offers to rescan the project's components for missing documentation. */
@Component
class RefreshKnowledgeGapsAction(
    private val insightsRefreshApi: InsightsRefreshApi,
) : TeamActionHandler {
    override val area = TeamArea.KNOWLEDGE
    override val risk = BuddyProposalRisk.BULK
    override val spec = BuddyToolSpecDto(
        name = "refresh_knowledge_gaps",
        description = "Offer to rescan this project's components for missing documentation. It asks the AI " +
            "service and takes a while. Takes no arguments.",
        parameters = stringFields(required = emptyList()),
    )

    override fun draft(call: BuddyToolCallDto, context: TeamToolContext): TeamActionDraft =
        TeamActionDraft.Proposed(
            params = JsonObject(emptyMap()),
            label = "Rescan for documentation gaps",
            preview = "Rescan this project's components for missing documentation, replacing the current list " +
                "of gaps. It asks the AI service and can take a while.",
        )

    override suspend fun perform(params: JsonObject, context: TeamToolContext): String {
        val refresh = insightsRefreshApi.refreshKnowledgeGaps(context.projectId)
        return "Rescanned ${refresh.componentCount} components: ${refresh.gapCount} are missing documentation."
    }
}
