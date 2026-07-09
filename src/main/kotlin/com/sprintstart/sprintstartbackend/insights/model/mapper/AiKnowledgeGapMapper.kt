package com.sprintstart.sprintstartbackend.insights.model.mapper

import com.sprintstart.sprintstartbackend.insights.model.ai.AiKnowledgeGap
import com.sprintstart.sprintstartbackend.insights.model.entity.KnowledgeGap
import com.sprintstart.sprintstartbackend.insights.model.entity.KnowledgeGapOwner
import com.sprintstart.sprintstartbackend.insights.model.entity.KnowledgeGapSeverity
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Builds persistable [KnowledgeGap] aggregates from AI classification results.
 *
 * The parent gap is created first so its owners can hold the required back-reference; owners are
 * cascaded on save. The AI severity string is mapped to [KnowledgeGapSeverity] and the ISO-8601
 * timestamp to an [Instant].
 */
@Component
class AiKnowledgeGapMapper {
    fun toEntity(aiGap: AiKnowledgeGap): KnowledgeGap {
        val gap = KnowledgeGap(
            component = aiGap.component,
            lastUpdated = Instant.parse(aiGap.lastUpdated),
            severity = KnowledgeGapSeverity.fromApiValue(aiGap.severity),
            relatedQuestions = aiGap.relatedQuestions,
        )

        gap.missingTypes.addAll(aiGap.missingTypes)

        aiGap.owners.forEach { owner ->
            gap.owners.add(
                KnowledgeGapOwner(
                    externalUserId = owner.id,
                    username = owner.username,
                    firstname = owner.firstname,
                    lastname = owner.lastname,
                    workingArea = owner.workingArea,
                    knowledgeGap = gap,
                ),
            )
        }

        return gap
    }
}
