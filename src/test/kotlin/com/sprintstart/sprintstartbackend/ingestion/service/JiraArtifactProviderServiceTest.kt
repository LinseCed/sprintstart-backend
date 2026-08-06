package com.sprintstart.sprintstartbackend.ingestion.service

import com.sprintstart.sprintstartbackend.ingestion.external.model.SourceSystem
import com.sprintstart.sprintstartbackend.ingestion.model.dto.JiraAuthor
import com.sprintstart.sprintstartbackend.ingestion.model.dto.JiraIssueHistory
import com.sprintstart.sprintstartbackend.ingestion.model.dto.JiraIssueType
import com.sprintstart.sprintstartbackend.ingestion.model.dto.JiraProject
import com.sprintstart.sprintstartbackend.ingestion.model.dto.command.JiraArtifactCommand
import com.sprintstart.sprintstartbackend.ingestion.model.entity.Artifact
import com.sprintstart.sprintstartbackend.ingestion.model.entity.ArtifactType
import com.sprintstart.sprintstartbackend.ingestion.model.entity.IngestionRun
import com.sprintstart.sprintstartbackend.ingestion.model.mapper.ArtifactMetadataJsonMapper
import com.sprintstart.sprintstartbackend.ingestion.repository.ArtifactRepository
import com.sprintstart.sprintstartbackend.ingestion.repository.IngestionRunRepository
import com.sprintstart.sprintstartbackend.ingestion.service.provider.JiraArtifactProviderService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * ⚠️ **What this fixes failed silently.** Starter-work mining's candidate filter is
 * `state == "OPEN"`, and nothing ever set `state` on a Jira issue — so a project whose tracker is
 * Jira had a corpus full of issues and mined an empty pool. That reads as *"no good first issues
 * here"*, not as *"we cannot see your tracker"*, which is why it survived the connector landing.
 */
class JiraArtifactProviderServiceTest {
    private val ingestionRunRepository = mockk<IngestionRunRepository>()
    private val artifactRepository = mockk<ArtifactRepository>()
    private val service = JiraArtifactProviderService(
        ingestionRunRepository,
        artifactRepository,
        ArtifactMetadataJsonMapper(ObjectMapper()),
    )

    private val runId = UUID.randomUUID()
    private val issueId = "10042"
    private val now: Instant = Instant.parse("2026-08-06T09:00:00Z")

    private fun author() = JiraAuthor(displayName = "Grace Hopper", active = true, createdAt = now, updatedAt = now)

    private fun command(statusCategory: String, statusName: String = statusCategory) = JiraArtifactCommand(
        ingestionRunId = runId,
        sourceSystem = SourceSystem.JIRA,
        sourceId = "instance-1",
        sourceUrl = "https://example.test/browse/ONB-42",
        artifactType = ArtifactType.ISSUE,
        issueType = JiraIssueType(name = "Task", description = ""),
        issueId = issueId,
        issueKey = "ONB-42",
        summary = "Run the sprint retro",
        description = "Facilitate it and write the notes up.",
        createdBy = author(),
        reportedBy = author(),
        assignee = null,
        createdAt = now,
        updatedAt = now,
        project = JiraProject(key = "ONB", name = "Onboarding", projectTypeKey = "software"),
        history = JiraIssueHistory(historyItems = emptyList()),
        comments = emptyList(),
        statusName = statusName,
        statusDescription = "",
        statusCategory = statusCategory,
        projectIds = setOf(UUID.randomUUID()),
    )

    private fun run() = mockk<IngestionRun>(relaxed = true)

    private fun newIssue(statusCategory: String, statusName: String = statusCategory): Artifact {
        val saved = slot<Artifact>()
        every { artifactRepository.findBySourceId(issueId) } returns null
        every { ingestionRunRepository.findByIdForUpdate(runId) } returns Optional.of(run())
        every { artifactRepository.save(capture(saved)) } answers { saved.captured }

        service.persistArtifact(command(statusCategory, statusName))

        return saved.captured
    }

    @Test
    fun `an unfinished issue is ingested as open, so mining can see it`() {
        assertThat(newIssue("In Progress").state).isEqualTo("OPEN")
    }

    @Test
    fun `a finished issue is ingested as closed`() {
        assertThat(newIssue("Done").state).isEqualTo("CLOSED")
    }

    /**
     * Folded on Jira's *category*, never the status name: one board's done column is called
     * "Shipped" and another's "Akzeptiert", and matching names would work only for teams who
     * happen to write English.
     */
    @Test
    fun `a done column called something else is still closed`() {
        assertThat(newIssue(statusCategory = "Done", statusName = "Akzeptiert").state).isEqualTo("CLOSED")
    }

    /**
     * ⚠️ Refreshed on re-ingest **unconditionally**, like GitHub's. A ticket moving to Done shifts
     * no text, so gating this on the title/description check would leave finished work in the
     * starter-work pool until somebody happened to edit its description.
     */
    @Test
    fun `a re-fetched issue picks up its new state even though the text is unchanged`() {
        val existing = Artifact(
            sourceSystem = SourceSystem.JIRA,
            sourceId = issueId,
            sourceUrl = null,
            artifactType = ArtifactType.ISSUE,
            title = "Run the sprint retro",
            content = "Facilitate it and write the notes up.",
            mime = null,
            language = null,
            state = "OPEN",
            createdAtSource = now,
            updatedAtSource = now,
            ingestionRun = run(),
            hash = null,
        )
        every { artifactRepository.findBySourceId(issueId) } returns existing
        every { ingestionRunRepository.findByIdForUpdate(runId) } returns Optional.of(run())

        service.persistArtifact(command("Done"))

        assertThat(existing.state).isEqualTo("CLOSED")
    }
}
