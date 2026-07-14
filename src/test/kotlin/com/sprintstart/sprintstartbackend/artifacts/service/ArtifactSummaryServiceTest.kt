package com.sprintstart.sprintstartbackend.artifacts.service

import com.sprintstart.sprintstartbackend.artifacts.ArtifactSummaryAiClient
import com.sprintstart.sprintstartbackend.artifacts.model.ai.AiArtifactSummaryCitation
import com.sprintstart.sprintstartbackend.artifacts.model.ai.AiArtifactSummaryResponse
import com.sprintstart.sprintstartbackend.artifacts.model.entity.ArtifactSummary
import com.sprintstart.sprintstartbackend.artifacts.model.mapper.ArtifactSummaryMapper
import com.sprintstart.sprintstartbackend.artifacts.repository.ArtifactSummaryRepository
import com.sprintstart.sprintstartbackend.ingestion.external.ArtifactIngestionApi
import com.sprintstart.sprintstartbackend.upload.external.UploadApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID

class ArtifactSummaryServiceTest {
    private val artifactSummaryRepository = mockk<ArtifactSummaryRepository>()
    private val artifactSummaryAiClient = mockk<ArtifactSummaryAiClient>()
    private val artifactSummaryMapper = ArtifactSummaryMapper()
    private val artifactIngestionApi = mockk<ArtifactIngestionApi>()
    private val uploadApi = mockk<UploadApi>()

    private val service = ArtifactSummaryService(
        artifactSummaryRepository = artifactSummaryRepository,
        artifactSummaryAiClient = artifactSummaryAiClient,
        artifactSummaryMapper = artifactSummaryMapper,
        artifactIngestionApi = artifactIngestionApi,
        uploadApi = uploadApi,
    )

    private val artifactId = UUID.randomUUID()

    private fun aiResponse(summary: String = "A summary.") = AiArtifactSummaryResponse(
        artifactId = artifactId.toString(),
        summary = summary,
        citations = listOf(
            AiArtifactSummaryCitation(
                artifactId = artifactId.toString(),
                filename = "README.md",
                sourceUrl = "https://github.com/example/repo",
            ),
        ),
    )

    @Test
    fun `getSummary serves a cached summary when the hash still matches`() = runTest {
        every { uploadApi.getHash(artifactId) } returns null
        every { artifactIngestionApi.exists(artifactId) } returns true
        every { artifactIngestionApi.getHash(artifactId) } returns "hash-1"

        val cached = ArtifactSummary(artifactId = artifactId, summary = "Cached summary.", sourceHash = "hash-1")
        every { artifactSummaryRepository.findById(artifactId) } returns Optional.of(cached)

        val result = service.getSummary(artifactId)

        assertEquals("Cached summary.", result.summary)
        coVerify(exactly = 0) { artifactSummaryAiClient.summarize(any(), any()) }
    }

    @Test
    fun `getSummary regenerates and caches when the hash changed`() = runTest {
        every { uploadApi.getHash(artifactId) } returns null
        every { artifactIngestionApi.exists(artifactId) } returns true
        every { artifactIngestionApi.getHash(artifactId) } returns "hash-2"

        val stale = ArtifactSummary(artifactId = artifactId, summary = "Stale summary.", sourceHash = "hash-1")
        every { artifactSummaryRepository.findById(artifactId) } returns Optional.of(stale)
        coEvery { artifactSummaryAiClient.summarize(artifactId, any()) } returns aiResponse("Fresh summary.")
        every { artifactSummaryRepository.save(any()) } answers { firstArg() }

        val result = service.getSummary(artifactId)

        assertEquals("Fresh summary.", result.summary)
        verify(exactly = 1) {
            artifactSummaryRepository.save(
                match { it.summary == "Fresh summary." && it.sourceHash == "hash-2" },
            )
        }
    }

    @Test
    fun `getSummary generates and caches when nothing is cached yet`() = runTest {
        every { uploadApi.getHash(artifactId) } returns null
        every { artifactIngestionApi.exists(artifactId) } returns true
        every { artifactIngestionApi.getHash(artifactId) } returns "hash-1"
        every { artifactSummaryRepository.findById(artifactId) } returns Optional.empty()
        coEvery { artifactSummaryAiClient.summarize(artifactId, any()) } returns aiResponse()
        every { artifactSummaryRepository.save(any()) } answers { firstArg() }

        val result = service.getSummary(artifactId)

        assertEquals("A summary.", result.summary)
        assertEquals(1, result.citations.size)
        assertEquals("README.md", result.citations[0].filename)
        verify(exactly = 1) { artifactSummaryRepository.save(any()) }
    }

    @Test
    fun `getSummary serves an uploaded artifact via its own hash`() = runTest {
        every { uploadApi.getHash(artifactId) } returns "uploaded-hash"
        every { artifactSummaryRepository.findById(artifactId) } returns Optional.empty()
        coEvery { artifactSummaryAiClient.summarize(artifactId, any()) } returns aiResponse()
        every { artifactSummaryRepository.save(any()) } answers { firstArg() }

        service.getSummary(artifactId)

        verify(exactly = 0) { artifactIngestionApi.exists(any()) }
        coVerify(exactly = 1) { artifactSummaryAiClient.summarize(artifactId, any()) }
    }

    @Test
    fun `getSummary regenerates without caching when the artifact has no hash on record`() = runTest {
        every { uploadApi.getHash(artifactId) } returns null
        every { artifactIngestionApi.exists(artifactId) } returns true
        every { artifactIngestionApi.getHash(artifactId) } returns null
        coEvery { artifactSummaryAiClient.summarize(artifactId, any()) } returns aiResponse()

        val result = service.getSummary(artifactId)

        assertEquals("A summary.", result.summary)
        verify(exactly = 0) { artifactSummaryRepository.save(any()) }
    }

    @Test
    fun `getSummary throws 404 when the artifact does not exist anywhere`() = runTest {
        every { uploadApi.getHash(artifactId) } returns null
        every { artifactIngestionApi.exists(artifactId) } returns false

        assertThrows<ResponseStatusException> {
            service.getSummary(artifactId)
        }

        coVerify(exactly = 0) { artifactSummaryAiClient.summarize(any(), any()) }
    }
}
