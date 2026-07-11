package com.sprintstart.sprintstartbackend.insights.service

import com.sprintstart.sprintstartbackend.insights.KnowledgeGapsAiClient
import com.sprintstart.sprintstartbackend.insights.model.ai.AiKnowledgeGap
import com.sprintstart.sprintstartbackend.insights.model.ai.AiKnowledgeGapsResponse
import com.sprintstart.sprintstartbackend.insights.model.entity.KnowledgeGap
import com.sprintstart.sprintstartbackend.insights.model.entity.KnowledgeGapSeverity
import com.sprintstart.sprintstartbackend.insights.model.mapper.AiKnowledgeGapMapper
import com.sprintstart.sprintstartbackend.insights.model.mapper.KnowledgeGapResponseMapper
import com.sprintstart.sprintstartbackend.insights.repository.KnowledgeGapRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional
import java.util.UUID

class KnowledgeGapsServiceTest {
    private val knowledgeGapRepository = mockk<KnowledgeGapRepository>()
    private val knowledgeGapsAiClient = mockk<KnowledgeGapsAiClient>()
    private val aiKnowledgeGapMapper = AiKnowledgeGapMapper()
    private val knowledgeGapResponseMapper = KnowledgeGapResponseMapper()

    private val service = KnowledgeGapsService(
        knowledgeGapRepository = knowledgeGapRepository,
        knowledgeGapsAiClient = knowledgeGapsAiClient,
        aiKnowledgeGapMapper = aiKnowledgeGapMapper,
        knowledgeGapResponseMapper = knowledgeGapResponseMapper,
    )

    private fun buildGap(
        component: String,
        severity: KnowledgeGapSeverity,
    ): KnowledgeGap {
        return KnowledgeGap(
            component = component,
            lastUpdated = Instant.parse("2025-05-01T00:00:00Z"),
            severity = severity,
        )
    }

    @Test
    fun `getKnowledgeGaps orders by severity then component`() {
        val lowGap = buildGap("frontend-portal", KnowledgeGapSeverity.LOW)
        val highB = buildGap("payment-service", KnowledgeGapSeverity.HIGH)
        val highA = buildGap("auth-service", KnowledgeGapSeverity.HIGH)
        every { knowledgeGapRepository.findAll() } returns listOf(lowGap, highB, highA)

        val overview = service.getKnowledgeGaps()

        assertEquals(
            listOf("auth-service", "payment-service", "frontend-portal"),
            overview.gaps.map { it.component },
        )
    }

    @Test
    fun `getKnowledgeGap maps fields, exposes lowercase severity and empty owners`() {
        val gap = buildGap("auth-service", KnowledgeGapSeverity.HIGH)
        gap.missingTypes.addAll(listOf("runbook", "adr"))
        gap.presentTypes.add("readme")
        every { knowledgeGapRepository.findById(gap.id) } returns Optional.of(gap)

        val detail = service.getKnowledgeGap(gap.id)

        assertEquals(gap.id, detail.id)
        assertEquals("auth-service", detail.component)
        assertEquals(listOf("runbook", "adr"), detail.missingTypes)
        assertEquals(listOf("readme"), detail.presentTypes)
        assertEquals("high", detail.severity)
        assertTrue(detail.owners.isEmpty())
    }

    @Test
    fun `getKnowledgeGap throws 404 when the gap does not exist`() {
        val missingId = UUID.randomUUID()
        every { knowledgeGapRepository.findById(missingId) } returns Optional.empty()

        val exception = assertThrows<ResponseStatusException> {
            service.getKnowledgeGap(missingId)
        }
        assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
    }

    @Test
    fun `refreshKnowledgeGaps classifies via the AI service and rebuilds the cache`() = runTest {
        val aiResponse = AiKnowledgeGapsResponse(
            gaps = listOf(
                AiKnowledgeGap(
                    component = "auth-service",
                    missingTypes = listOf("runbook", "adr"),
                    presentTypes = listOf("readme"),
                    lastUpdated = "2025-05-01T00:00:00Z",
                    severity = "high",
                ),
            ),
        )
        coEvery { knowledgeGapsAiClient.detectKnowledgeGaps(any()) } returns aiResponse
        every { knowledgeGapRepository.deleteAll() } just Runs
        val savedSlot = slot<List<KnowledgeGap>>()
        every { knowledgeGapRepository.saveAll(capture(savedSlot)) } answers { savedSlot.captured.toMutableList() }

        val result = service.refreshKnowledgeGaps()

        assertEquals(1, result.gapCount)

        val persisted = savedSlot.captured.first()
        assertEquals("auth-service", persisted.component)
        assertEquals(KnowledgeGapSeverity.HIGH, persisted.severity)
        assertEquals(Instant.parse("2025-05-01T00:00:00Z"), persisted.lastUpdated)
        assertEquals(listOf("runbook", "adr"), persisted.missingTypes)
        assertEquals(listOf("readme"), persisted.presentTypes)

        coVerify(exactly = 1) { knowledgeGapsAiClient.detectKnowledgeGaps(any()) }
        verifyOrder {
            knowledgeGapRepository.deleteAll()
            knowledgeGapRepository.saveAll(any<List<KnowledgeGap>>())
        }
    }
}
