package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ChangeClassification
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ChangeType
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphChange
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphVersion
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyGraphChangeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyGraphVersionRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CompetencyGraphVersionServiceTest {
    private val versionRepository: CompetencyGraphVersionRepository = mockk()
    private val changeRepository: CompetencyGraphChangeRepository = mockk()
    private val competencyRepository: CompetencyRepository = mockk()
    private val classifier: GraphChangeClassifier = mockk()
    private val service =
        CompetencyGraphVersionService(versionRepository, changeRepository, competencyRepository, classifier)

    @Nested
    inner class CurrentVersion {
        @Test
        fun `defaults to 1 when no row exists`() {
            every { versionRepository.findTopByOrderByVersionDesc() } returns null

            assertThat(service.currentVersion()).isEqualTo(1)
        }

        @Test
        fun `returns the stored latest version`() {
            every { versionRepository.findTopByOrderByVersionDesc() } returns
                CompetencyGraphVersion(version = 5, classification = ChangeClassification.ADDITIVE)

            assertThat(service.currentVersion()).isEqualTo(5)
        }
    }

    @Nested
    inner class RecordChanges {
        @Test
        fun `records a node addition against the pending version`() {
            every { versionRepository.findTopByOrderByVersionDesc() } returns
                CompetencyGraphVersion(version = 3, classification = ChangeClassification.ADDITIVE)
            val savedSlot = slot<CompetencyGraphChange>()
            every { changeRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            service.recordNodeAdded("kotlin")

            assertThat(savedSlot.captured.version).isEqualTo(4)
            assertThat(savedSlot.captured.changeType).isEqualTo(ChangeType.NODE_ADDED)
            assertThat(savedSlot.captured.competencyKey).isEqualTo("kotlin")
        }

        @Test
        fun `records an edge addition against the pending version`() {
            every { versionRepository.findTopByOrderByVersionDesc() } returns null
            val savedSlot = slot<CompetencyGraphChange>()
            every { changeRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            service.recordEdgeAdded("kotlin", "our-domain-model", EdgeKind.PREREQUISITE)

            assertThat(savedSlot.captured.version).isEqualTo(1)
            assertThat(savedSlot.captured.changeType).isEqualTo(ChangeType.EDGE_ADDED)
            assertThat(savedSlot.captured.fromKey).isEqualTo("kotlin")
            assertThat(savedSlot.captured.toKey).isEqualTo("our-domain-model")
            assertThat(savedSlot.captured.edgeKind).isEqualTo(EdgeKind.PREREQUISITE)
        }
    }

    @Nested
    inner class Bump {
        @Test
        fun `is a no-op when nothing was recorded since the last bump`() {
            every { versionRepository.findTopByOrderByVersionDesc() } returns
                CompetencyGraphVersion(version = 3, classification = ChangeClassification.ADDITIVE)
            every { changeRepository.findAllByVersion(4) } returns emptyList()

            val result = service.bump()

            assertThat(result).isEqualTo(3)
            verify(exactly = 0) { versionRepository.save(any()) }
        }

        @Test
        fun `returns 1 when nothing was ever recorded and no row exists`() {
            every { versionRepository.findTopByOrderByVersionDesc() } returns null
            every { changeRepository.findAllByVersion(1) } returns emptyList()

            assertThat(service.bump()).isEqualTo(1)
        }

        @Test
        fun `classifies pending changes and appends a new version row when something was recorded`() {
            every { versionRepository.findTopByOrderByVersionDesc() } returns
                CompetencyGraphVersion(version = 3, classification = ChangeClassification.ADDITIVE)
            val pendingChanges = listOf(
                CompetencyGraphChange(version = 4, changeType = ChangeType.NODE_ADDED, competencyKey = "kotlin"),
            )
            every { changeRepository.findAllByVersion(4) } returns pendingChanges
            every { competencyRepository.findAll() } returns listOf(
                Competency(key = "kotlin", label = "Kotlin", kind = CompetencyKind.SKILL),
            )
            every { classifier.classify(pendingChanges, any()) } returns ChangeClassification.ADDITIVE
            val savedSlot = slot<CompetencyGraphVersion>()
            every { versionRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            val result = service.bump()

            assertThat(result).isEqualTo(4)
            assertThat(savedSlot.captured.version).isEqualTo(4)
            assertThat(savedSlot.captured.classification).isEqualTo(ChangeClassification.ADDITIVE)
        }
    }
}
