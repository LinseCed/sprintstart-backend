package com.sprintstart.sprintstartbackend.insights.model.ai

import kotlinx.serialization.Serializable

/**
 * Knowledge gaps returned by the AI service.
 */
@Serializable
data class AiKnowledgeGapsResponse(
    val gaps: List<AiKnowledgeGap>,
)

/**
 * A single component that is missing critical documentation.
 *
 * @property component name of the component that has gaps
 * @property missingTypes document types the component is missing, for example "runbook" or "adr"
 * @property lastUpdated ISO-8601 timestamp of the component's most recent activity
 * @property owners people responsible for the component
 * @property severity impact level, one of "high", "medium" or "low"
 * @property relatedQuestions number of user questions related to this component
 */
@Serializable
data class AiKnowledgeGap(
    val component: String,
    val missingTypes: List<String>,
    val lastUpdated: String,
    val owners: List<AiKnowledgeGapOwner>,
    val severity: String,
    val relatedQuestions: Int,
)

/**
 * A person responsible for a component, as reported by the AI service.
 *
 * @property id identifier of the user in the upstream system
 */
@Serializable
data class AiKnowledgeGapOwner(
    val id: String,
    val username: String,
    val firstname: String,
    val lastname: String,
    val workingArea: String,
)
