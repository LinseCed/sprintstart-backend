package com.sprintstart.sprintstartbackend.insights.model.ai

import kotlinx.serialization.Serializable

/**
 * Request sent to the AI service to (re)compute recurring-question groups.
 *
 * The AI service owns the raw questions and performs the semantic clustering and PII redaction, so
 * the backend does not send any question data. [limit] optionally caps how many groups the service
 * should return; `null` requests all groups.
 */
@Serializable
data class AiFaqGroupingRequest(
    val limit: Int? = null,
)
