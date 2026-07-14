package com.sprintstart.sprintstartbackend.artifacts.model.mapper

import com.sprintstart.sprintstartbackend.artifacts.model.ai.AiArtifactSummaryCitation
import com.sprintstart.sprintstartbackend.artifacts.model.ai.AiArtifactSummaryResponse
import com.sprintstart.sprintstartbackend.artifacts.model.entity.ArtifactSummary
import com.sprintstart.sprintstartbackend.artifacts.model.entity.ArtifactSummaryCitation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ArtifactSummaryMapperTest {
    private val mapper = ArtifactSummaryMapper()

    private val artifactId = UUID.randomUUID()
    private val citedArtifactId = UUID.randomUUID()

    @Test
    fun `toEntity builds an entity carrying the given hash and citations`() {
        val aiResponse = AiArtifactSummaryResponse(
            artifactId = artifactId.toString(),
            summary = "A short summary.",
            citations = listOf(
                AiArtifactSummaryCitation(
                    artifactId = citedArtifactId.toString(),
                    filename = "README.md",
                    sourceUrl = "https://github.com/example/repo",
                ),
            ),
        )

        val entity = mapper.toEntity(artifactId, "hash-1", aiResponse)

        assertEquals(artifactId, entity.artifactId)
        assertEquals("A short summary.", entity.summary)
        assertEquals("hash-1", entity.sourceHash)
        assertEquals(1, entity.citations.size)
        assertEquals(citedArtifactId, entity.citations[0].citedArtifactId)
        assertEquals("README.md", entity.citations[0].filename)
    }

    @Test
    fun `toEntity drops citations with a non-UUID artifact id instead of failing`() {
        val aiResponse = AiArtifactSummaryResponse(
            artifactId = artifactId.toString(),
            summary = "A short summary.",
            citations = listOf(
                AiArtifactSummaryCitation(artifactId = "not-a-uuid", filename = "README.md"),
            ),
        )

        val entity = mapper.toEntity(artifactId, "hash-1", aiResponse)

        assertTrue(entity.citations.isEmpty())
    }

    @Test
    fun `toEntity carries a null hash through unchanged`() {
        val aiResponse =
            AiArtifactSummaryResponse(artifactId = artifactId.toString(), summary = "x", citations = emptyList())

        val entity = mapper.toEntity(artifactId, null, aiResponse)

        assertNull(entity.sourceHash)
    }

    @Test
    fun `toResponse maps the entity's citations`() {
        val entity = ArtifactSummary(artifactId = artifactId, summary = "A summary.", sourceHash = "hash-1")
        entity.citations = mutableListOf(
            ArtifactSummaryCitation(
                artifactSummary = entity,
                citedArtifactId = citedArtifactId,
                filename = "README.md",
                sourceUrl = null,
            ),
        )

        val response = mapper.toResponse(entity)

        assertEquals(artifactId, response.artifactId)
        assertEquals("A summary.", response.summary)
        assertEquals(1, response.citations.size)
        assertEquals(citedArtifactId, response.citations[0].artifactId)
    }
}
