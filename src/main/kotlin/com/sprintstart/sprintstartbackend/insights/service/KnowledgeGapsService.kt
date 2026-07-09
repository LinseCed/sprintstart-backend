package com.sprintstart.sprintstartbackend.insights.service

import com.sprintstart.sprintstartbackend.insights.KnowledgeGapsAiClient
import com.sprintstart.sprintstartbackend.insights.model.ai.AiKnowledgeGapsRequest
import com.sprintstart.sprintstartbackend.insights.model.dto.response.KnowledgeGapResponse
import com.sprintstart.sprintstartbackend.insights.model.dto.response.KnowledgeGapsOverviewResponse
import com.sprintstart.sprintstartbackend.insights.model.dto.response.RefreshKnowledgeGapsResponse
import com.sprintstart.sprintstartbackend.insights.model.entity.KnowledgeGap
import com.sprintstart.sprintstartbackend.insights.model.mapper.AiKnowledgeGapMapper
import com.sprintstart.sprintstartbackend.insights.model.mapper.KnowledgeGapResponseMapper
import com.sprintstart.sprintstartbackend.insights.repository.KnowledgeGapRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Serves the PM knowledge-gaps panel and keeps the persisted classification cache up to date.
 *
 * Read endpoints are served entirely from the local cache. A refresh delegates the missing-runbook/
 * ADR detection to the AI service and then rebuilds the cache from the result, so the dashboard
 * reads stay fast and independent of AI latency.
 */
@Service
class KnowledgeGapsService(
    private val knowledgeGapRepository: KnowledgeGapRepository,
    private val knowledgeGapsAiClient: KnowledgeGapsAiClient,
    private val aiKnowledgeGapMapper: AiKnowledgeGapMapper,
    private val knowledgeGapResponseMapper: KnowledgeGapResponseMapper,
) {
    /**
     * Returns all cached knowledge gaps, most severe first and then by related-question count.
     */
    @Transactional(readOnly = true)
    fun getKnowledgeGaps(): KnowledgeGapsOverviewResponse {
        val gaps = knowledgeGapRepository
            .findAll()
            .sortedWith(compareBy({ it.severity.ordinal }, { -it.relatedQuestions }))
        return knowledgeGapResponseMapper.toOverviewResponse(gaps)
    }

    /**
     * Returns a single cached knowledge gap.
     *
     * @throws ResponseStatusException 404 if no gap with [gapId] exists.
     */
    @Transactional(readOnly = true)
    fun getKnowledgeGap(gapId: UUID): KnowledgeGapResponse {
        val gap = knowledgeGapRepository.findById(gapId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Knowledge gap with id $gapId not found")
        }
        return knowledgeGapResponseMapper.toResponse(gap)
    }

    /**
     * Reclassifies knowledge gaps via the AI service and replaces the cache.
     *
     * The previous gaps are removed and the freshly classified result is stored as the new cache.
     *
     * @throws com.sprintstart.sprintstartbackend.insights.model.exceptions.KnowledgeGapsAiException
     *   if the AI service does not return a classification result.
     */
    suspend fun refreshKnowledgeGaps(): RefreshKnowledgeGapsResponse {
        val aiResponse = knowledgeGapsAiClient.detectKnowledgeGaps(AiKnowledgeGapsRequest())
        val gaps: List<KnowledgeGap> = aiResponse.gaps.map { aiKnowledgeGapMapper.toEntity(it) }

        knowledgeGapRepository.deleteAll()
        knowledgeGapRepository.saveAll(gaps)

        return RefreshKnowledgeGapsResponse(gapCount = gaps.size)
    }
}
