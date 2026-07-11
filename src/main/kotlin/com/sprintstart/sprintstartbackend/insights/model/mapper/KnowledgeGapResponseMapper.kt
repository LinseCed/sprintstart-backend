package com.sprintstart.sprintstartbackend.insights.model.mapper

import com.sprintstart.sprintstartbackend.insights.model.dto.response.KnowledgeGapOwnerResponse
import com.sprintstart.sprintstartbackend.insights.model.dto.response.KnowledgeGapResponse
import com.sprintstart.sprintstartbackend.insights.model.dto.response.KnowledgeGapsOverviewResponse
import com.sprintstart.sprintstartbackend.insights.model.entity.KnowledgeGap
import org.springframework.stereotype.Component

/**
 * Converts persisted knowledge gaps into API response DTOs.
 *
 * The severity enum is rendered as its lowercase API value. Owners are not stored on the gap; the
 * caller resolves them from the component-ownership mapping and passes them in per component.
 */
@Component
class KnowledgeGapResponseMapper {
    fun toOverviewResponse(
        gaps: List<KnowledgeGap>,
        ownersByComponent: Map<String, List<KnowledgeGapOwnerResponse>>,
    ): KnowledgeGapsOverviewResponse {
        return KnowledgeGapsOverviewResponse(
            gaps = gaps.map { toResponse(it, ownersByComponent[it.component] ?: emptyList()) },
        )
    }

    fun toResponse(
        gap: KnowledgeGap,
        owners: List<KnowledgeGapOwnerResponse>,
    ): KnowledgeGapResponse {
        return KnowledgeGapResponse(
            id = gap.id,
            component = gap.component,
            missingTypes = gap.missingTypes.toList(),
            presentTypes = gap.presentTypes.toList(),
            lastUpdated = gap.lastUpdated,
            owners = owners,
            severity = gap.severity.apiValue,
        )
    }
}
