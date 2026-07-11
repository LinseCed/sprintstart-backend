package com.sprintstart.sprintstartbackend.insights.model.mapper

import com.sprintstart.sprintstartbackend.insights.model.dto.response.KnowledgeGapResponse
import com.sprintstart.sprintstartbackend.insights.model.dto.response.KnowledgeGapsOverviewResponse
import com.sprintstart.sprintstartbackend.insights.model.entity.KnowledgeGap
import org.springframework.stereotype.Component

/**
 * Converts persisted knowledge gaps into API response DTOs.
 *
 * The severity enum is rendered as its lowercase API value. Owners are not stored on the gap; they
 * are resolved from a separate component-ownership mapping and are empty until that mapping is
 * populated.
 */
@Component
class KnowledgeGapResponseMapper {
    fun toOverviewResponse(gaps: List<KnowledgeGap>): KnowledgeGapsOverviewResponse {
        return KnowledgeGapsOverviewResponse(
            gaps = gaps.map { toResponse(it) },
        )
    }

    fun toResponse(gap: KnowledgeGap): KnowledgeGapResponse {
        return KnowledgeGapResponse(
            id = gap.id,
            component = gap.component,
            missingTypes = gap.missingTypes.toList(),
            presentTypes = gap.presentTypes.toList(),
            lastUpdated = gap.lastUpdated,
            owners = emptyList(),
            severity = gap.severity.apiValue,
        )
    }
}
