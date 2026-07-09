package com.sprintstart.sprintstartbackend.insights.model.mapper

import com.sprintstart.sprintstartbackend.insights.model.dto.response.KnowledgeGapOwnerResponse
import com.sprintstart.sprintstartbackend.insights.model.dto.response.KnowledgeGapResponse
import com.sprintstart.sprintstartbackend.insights.model.dto.response.KnowledgeGapsOverviewResponse
import com.sprintstart.sprintstartbackend.insights.model.entity.KnowledgeGap
import org.springframework.stereotype.Component

/**
 * Converts persisted knowledge gaps into API response DTOs.
 *
 * The upstream owner id
 * ([com.sprintstart.sprintstartbackend.insights.model.entity.KnowledgeGapOwner.externalUserId]) is
 * exposed as the owner id, and the severity enum is rendered as its lowercase API value.
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
            lastUpdated = gap.lastUpdated,
            owners = gap.owners.map { owner ->
                KnowledgeGapOwnerResponse(
                    id = owner.externalUserId,
                    username = owner.username,
                    firstname = owner.firstname,
                    lastname = owner.lastname,
                    workingArea = owner.workingArea,
                )
            },
            severity = gap.severity.apiValue,
            relatedQuestions = gap.relatedQuestions,
        )
    }
}
