package com.sprintstart.sprintstartbackend.ingestion.model.mapper.ingestion

import com.sprintstart.sprintstartbackend.ingestion.model.entity.Artifact
import com.sprintstart.sprintstartbackend.ingestion.model.entity.ArtifactType
import com.sprintstart.sprintstartbackend.ingestion.model.entity.IngestionRun
import com.sprintstart.sprintstartbackend.ingestion.model.entity.IngestionRunStatus
import com.sprintstart.sprintstartbackend.ingestion.model.entity.SourceSystem
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ArtifactAiMapperTest {
    private val mapper = ArtifactAiMapper()

    private fun ingestionRun() = IngestionRun(
        id = UUID.randomUUID(),
        sourceSystem = SourceSystem.GITHUB,
        status = IngestionRunStatus.RUNNING,
    )

    @Test
    fun `toIngestRequest forwards issue state and labels`() {
        val artifact = Artifact(
            sourceSystem = SourceSystem.GITHUB,
            sourceId = "github:owner/repo:ISSUE:42",
            sourceUrl = "https://github.com/owner/repo/issues/42",
            artifactType = ArtifactType.ISSUE,
            title = "Issue #42 Bug report",
            content = "Something broke",
            mime = null,
            language = null,
            state = "OPEN",
            labels = mutableListOf("bug", "good first issue"),
            ingestionRun = ingestionRun(),
            hash = "hash",
            createdAtSource = null,
            updatedAtSource = null,
        )

        val result = mapper.toIngestRequest(artifact)

        assertThat(result.state).isEqualTo("OPEN")
        assertThat(result.labels).containsExactly("bug", "good first issue")
    }

    @Test
    fun `toIngestRequest defaults to null state and empty labels for a non-issue artifact`() {
        val artifact = Artifact(
            sourceSystem = SourceSystem.GITHUB,
            sourceId = "github:owner/repo:FILE:src/main/App.kt",
            sourceUrl = "https://github.com/owner/repo/blob/main/src/main/App.kt",
            artifactType = ArtifactType.FILE,
            title = "App.kt",
            content = "fun main() = Unit",
            mime = "text/x-kotlin",
            language = "Kotlin",
            ingestionRun = ingestionRun(),
            hash = "hash",
            createdAtSource = null,
            updatedAtSource = null,
        )

        val result = mapper.toIngestRequest(artifact)

        assertThat(result.state).isNull()
        assertThat(result.labels).isEmpty()
    }
}
