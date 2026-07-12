package com.sprintstart.sprintstartbackend.github.service

import com.sprintstart.sprintstartbackend.connectors.github.models.GithubRepositoryConfig
import com.sprintstart.sprintstartbackend.connectors.github.models.GithubRepositoryConnection
import com.sprintstart.sprintstartbackend.connectors.github.models.GithubUser
import com.sprintstart.sprintstartbackend.connectors.github.models.GithubUserPat
import com.sprintstart.sprintstartbackend.connectors.github.models.api.requests.ConfigureRepositoryRequest
import com.sprintstart.sprintstartbackend.connectors.github.models.api.requests.GetRepositoryConfigRequest
import com.sprintstart.sprintstartbackend.connectors.github.models.api.requests.UpdateSchedule
import com.sprintstart.sprintstartbackend.connectors.github.models.exceptions.RepositoryConfigNotFoundException
import com.sprintstart.sprintstartbackend.connectors.github.repository.GithubRepositoryConfigRepository
import com.sprintstart.sprintstartbackend.connectors.github.repository.GithubRepositoryConnectionRepository
import com.sprintstart.sprintstartbackend.connectors.github.service.GithubRepositoryConfigService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.Optional

class GithubRepositoryConfigServiceTest {
    private val configRepository = mockk<GithubRepositoryConfigRepository>()
    private val githubRepoRepository = mockk<GithubRepositoryConnectionRepository>()

    private lateinit var service: GithubRepositoryConfigService

    private val user = GithubUser(
        id = GithubUserPat("auth-id", "token-name"),
        token = "test-token",
    )

    @BeforeEach
    fun setUp() {
        service = GithubRepositoryConfigService(
            configRepository = configRepository,
            githubRepoRepository = githubRepoRepository,
        )
    }

    @Nested
    inner class CalculateNextSyncAt {
        @Test
        fun `returns future Instant for valid cron schedule`() {
            val before = Instant.now()
            val result = GithubRepositoryConfigService.calculateNextSyncAt("0 0 2 * * *")
            val after = Instant.now()

            assertThat(result).isNotNull
            assertThat(result).isAfterOrEqualTo(before)
        }

        @Test
        fun `returns null for invalid cron schedule`() {
            val result = GithubRepositoryConfigService.calculateNextSyncAt("invalid cron")

            assertThat(result).isNull()
        }

        @Test
        fun `returns null for empty schedule`() {
            val result = GithubRepositoryConfigService.calculateNextSyncAt("")

            assertThat(result).isNull()
        }
    }

    @Nested
    inner class Configure {
        @Test
        fun `updates autoUpdate and schedule`() {
            val repo = repoConnection("owner", "repo")
            val config = GithubRepositoryConfig(id = repo.id, repository = repo)
            every { githubRepoRepository.findByOwnerAndName("owner", "repo") } returns repo
            every { configRepository.findById(repo.id) } returns Optional.of(config)
            every { configRepository.save(config) } returns config

            val request = ConfigureRepositoryRequest(
                autoUpdate = true,
                schedule = UpdateSchedule(
                    seconds = listOf("0"),
                    minutes = listOf("30"),
                    hour = listOf("6"),
                    dayOfWeek = listOf("*"),
                    dayOfMonth = listOf("*"),
                    monthOfYear = listOf("*"),
                ),
            )

            service.configure("owner", "repo", request)

            assertThat(config.autoUpdate).isTrue()
            assertThat(config.schedule).isEqualTo("0 30 6 * * *")
            assertThat(config.nextSyncAt).isNotNull
            verify { configRepository.save(config) }
        }

        @Test
        fun `throws RepositoryConfigNotFoundException when no config exists`() {
            val repo = repoConnection("owner", "repo")
            every { githubRepoRepository.findByOwnerAndName("owner", "repo") } returns repo
            every { configRepository.findById(repo.id) } returns Optional.empty()

            val request = ConfigureRepositoryRequest(
                autoUpdate = true,
                schedule = UpdateSchedule(
                    seconds = listOf("0"),
                    minutes = listOf("30"),
                    hour = listOf("6"),
                    dayOfWeek = listOf("*"),
                    dayOfMonth = listOf("*"),
                    monthOfYear = listOf("*"),
                ),
            )

            assertThrows<RepositoryConfigNotFoundException> {
                service.configure("owner", "repo", request)
            }
        }
    }

    @Nested
    inner class ConfigureGlobal {
        @Test
        fun `updates all configs with given settings`() {
            val repo1 = repoConnection("owner1", "repo1")
            val repo2 = repoConnection("owner2", "repo2")
            val config1 = GithubRepositoryConfig(id = repo1.id, repository = repo1)
            val config2 = GithubRepositoryConfig(id = repo2.id, repository = repo2)
            every { configRepository.findAll() } returns listOf(config1, config2)
            every { configRepository.saveAll(any<Iterable<GithubRepositoryConfig>>()) } returns listOf(config1, config2)

            val request = ConfigureRepositoryRequest(
                autoUpdate = false,
                schedule = UpdateSchedule(
                    seconds = listOf("0"),
                    minutes = listOf("0"),
                    hour = listOf("3"),
                    dayOfWeek = listOf("*"),
                    dayOfMonth = listOf("*"),
                    monthOfYear = listOf("*"),
                ),
            )

            service.configureGlobal(request)

            assertThat(config1.autoUpdate).isFalse()
            assertThat(config1.schedule).isEqualTo("0 0 3 * * *")
            assertThat(config1.nextSyncAt).isNotNull
            assertThat(config2.autoUpdate).isFalse()
            assertThat(config2.schedule).isEqualTo("0 0 3 * * *")
            assertThat(config2.nextSyncAt).isNotNull
            verify { configRepository.saveAll(listOf(config1, config2)) }
        }
    }

    @Nested
    inner class GetConfigOfRepository {
        @Test
        fun `returns config mapped to response DTO`() {
            val repo = repoConnection("owner", "repo")
            val config = GithubRepositoryConfig(id = repo.id, repository = repo).apply {
                autoUpdate = true
                schedule = "0 0 2 * * *"
                nextSyncAt = Instant.parse("2026-01-01T02:00:00Z")
            }
            every { githubRepoRepository.findByOwnerAndName("owner", "repo") } returns repo
            every { configRepository.findById(repo.id) } returns Optional.of(config)

            val response = service.getConfigOfRepository(GetRepositoryConfigRequest("owner", "repo"))

            assertThat(response.repositoryOwner).isEqualTo("owner")
            assertThat(response.repositoryName).isEqualTo("repo")
            assertThat(response.autoUpdate).isTrue()
            assertThat(response.schedule).isEqualTo("0 0 2 * * *")
            assertThat(response.nextSyncAt).isEqualTo("2026-01-01T02:00:00Z")
        }
    }

    @Nested
    inner class FindAllRepositoriesDueForSync {
        @Test
        fun `returns connections for configs due at given time`() {
            val repo1 = repoConnection("owner1", "repo1")
            val repo2 = repoConnection("owner2", "repo2")
            val config1 = GithubRepositoryConfig(id = repo1.id, repository = repo1).apply {
                nextSyncAt = Instant.parse("2025-01-01T00:00:00Z")
            }
            val config2 = GithubRepositoryConfig(id = repo2.id, repository = repo2).apply {
                nextSyncAt = Instant.parse("2025-01-01T00:00:00Z")
            }
            val now = Instant.parse("2025-06-01T00:00:00Z")
            every { configRepository.findAllDue(now) } returns listOf(config1, config2)
            every { githubRepoRepository.findById(repo1.id) } returns Optional.of(repo1)
            every { githubRepoRepository.findById(repo2.id) } returns Optional.of(repo2)

            val result = service.findAllRepositoriesDueForSync(now)

            assertThat(result).containsExactly(repo1, repo2)
        }

        @Test
        fun `returns empty list when no configs are due`() {
            val now = Instant.parse("2025-06-01T00:00:00Z")
            every { configRepository.findAllDue(now) } returns emptyList()

            val result = service.findAllRepositoriesDueForSync(now)

            assertThat(result).isEmpty()
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun repoConnection(owner: String, name: String) = GithubRepositoryConnection(
        owner = owner,
        name = name,
        user = user,
    )
}
