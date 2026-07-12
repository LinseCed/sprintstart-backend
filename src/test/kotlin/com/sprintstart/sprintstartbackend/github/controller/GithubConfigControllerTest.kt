package com.sprintstart.sprintstartbackend.github.controller

import com.ninjasquad.springmockk.MockkBean
import com.sprintstart.sprintstartbackend.config.SecurityConfig
import com.sprintstart.sprintstartbackend.connectors.github.controller.GithubExceptionHandler
import com.sprintstart.sprintstartbackend.connectors.github.controller.GithubRepositoryConfigController
import com.sprintstart.sprintstartbackend.connectors.github.models.api.requests.ConfigureRepositoryRequest
import com.sprintstart.sprintstartbackend.connectors.github.models.api.requests.GetRepositoryConfigRequest
import com.sprintstart.sprintstartbackend.connectors.github.models.api.requests.UpdateSchedule
import com.sprintstart.sprintstartbackend.connectors.github.models.api.responses.GetRepositoryConfigResponse
import com.sprintstart.sprintstartbackend.connectors.github.models.exceptions.RepositoryConfigNotFoundException
import com.sprintstart.sprintstartbackend.connectors.github.models.exceptions.RepositoryNotConnectedException
import com.sprintstart.sprintstartbackend.connectors.github.service.GithubRepositoryConfigService
import io.mockk.every
import io.mockk.just
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

@WebMvcTest(controllers = [GithubRepositoryConfigController::class])
@AutoConfigureMockMvc
@Import(GithubExceptionHandler::class, SecurityConfig::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GithubConfigControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var configService: GithubRepositoryConfigService

    private val objectMapper = jacksonObjectMapper()

    private val adminJwt = jwt()
        .jwt { it.subject("mockId") }
        .authorities(SimpleGrantedAuthority("ROLE_ADMIN"))

    @Nested
    inner class ConfigureGlobal {
        @Test
        fun `returns 204 No Content when configuration is applied`() {
            every { configService.configureGlobal(any()) } just runs

            mockMvc
                .perform(
                    put("/api/v1/github/config/global")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validConfigBody())
                        .with(adminJwt),
                ).andExpect(status().isNoContent)

            verify { configService.configureGlobal(any()) }
        }

        @Test
        fun `returns 400 when schedule is invalid`() {
            mockMvc
                .perform(
                    put("/api/v1/github/config/global")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"schedule": {}, "autoUpdate": true}""")
                        .with(adminJwt),
                ).andExpect(status().isBadRequest)
        }

        @Test
        fun `returns 403 when user has insufficient role`() {
            val userJwt = jwt()
                .jwt { it.subject("mockId") }
                .authorities(SimpleGrantedAuthority("ROLE_USER"))

            mockMvc
                .perform(
                    put("/api/v1/github/config/global")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validConfigBody())
                        .with(userJwt),
                ).andExpect(status().isForbidden)
        }
    }

    @Nested
    inner class ConfigureRepository {
        @Test
        fun `returns 204 No Content when repository is configured`() {
            every { configService.configure("owner", "repo", any()) } just runs

            mockMvc
                .perform(
                    put("/api/v1/github/config/owner/repo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validConfigBody())
                        .with(adminJwt),
                ).andExpect(status().isNoContent)

            verify { configService.configure("owner", "repo", any()) }
        }

        @Test
        fun `returns 400 when repository is not connected`() {
            every { configService.configure("owner", "repo", any()) } throws
                RepositoryNotConnectedException("owner", "repo")

            mockMvc
                .perform(
                    put("/api/v1/github/config/owner/repo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validConfigBody())
                        .with(adminJwt),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.message").value("Repository owner/repo is not connected."))
        }

        @Test
        fun `returns 404 when config does not exist`() {
            every { configService.configure("owner", "repo", any()) } throws
                RepositoryConfigNotFoundException("owner", "repo")

            mockMvc
                .perform(
                    put("/api/v1/github/config/owner/repo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validConfigBody())
                        .with(adminJwt),
                ).andExpect(status().isNotFound)
                .andExpect(jsonPath("$.message").value("No config for GitHub repository owner/repo found."))
        }

        @Test
        fun `returns 400 when request body is invalid`() {
            mockMvc
                .perform(
                    put("/api/v1/github/config/owner/repo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"schedule": {}, "autoUpdate": true}""")
                        .with(adminJwt),
                ).andExpect(status().isBadRequest)
        }

        @Test
        fun `returns 403 when user has insufficient role`() {
            val userJwt = jwt()
                .jwt { it.subject("mockId") }
                .authorities(SimpleGrantedAuthority("ROLE_USER"))

            mockMvc
                .perform(
                    put("/api/v1/github/config/owner/repo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validConfigBody())
                        .with(userJwt),
                ).andExpect(status().isForbidden)
        }
    }

    @Nested
    inner class GetConfigOfRepository {
        @Test
        fun `returns 200 with config for connected repository`() {
            val response = GetRepositoryConfigResponse(
                id = UUID.randomUUID(),
                repositoryOwner = "owner",
                repositoryName = "repo",
                autoUpdate = true,
                schedule = "0 0 2 * * *",
                nextSyncAt = Instant.parse("2026-01-01T02:00:00Z"),
            )
            every {
                configService.getConfigOfRepository(GetRepositoryConfigRequest("owner", "repo"))
            } returns response

            mockMvc
                .perform(
                    get("/api/v1/github/config/owner/repo")
                        .with(adminJwt),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.repositoryOwner").value("owner"))
                .andExpect(jsonPath("$.repositoryName").value("repo"))
                .andExpect(jsonPath("$.autoUpdate").value(true))
                .andExpect(jsonPath("$.schedule").value("0 0 2 * * *"))
                .andExpect(jsonPath("$.nextSyncAt").value("2026-01-01T02:00:00Z"))
        }

        @Test
        fun `returns 400 when repository is not connected`() {
            every {
                configService.getConfigOfRepository(GetRepositoryConfigRequest("owner", "repo"))
            } throws RepositoryNotConnectedException("owner", "repo")

            mockMvc
                .perform(
                    get("/api/v1/github/config/owner/repo")
                        .with(adminJwt),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.message").value("Repository owner/repo is not connected."))
        }

        @Test
        fun `returns 404 when config does not exist`() {
            every {
                configService.getConfigOfRepository(GetRepositoryConfigRequest("owner", "repo"))
            } throws RepositoryConfigNotFoundException("owner", "repo")

            mockMvc
                .perform(
                    get("/api/v1/github/config/owner/repo")
                        .with(adminJwt),
                ).andExpect(status().isNotFound)
                .andExpect(jsonPath("$.message").value("No config for GitHub repository owner/repo found."))
        }

        @Test
        fun `returns 403 when user has insufficient role`() {
            val userJwt = jwt()
                .jwt { it.subject("mockId") }
                .authorities(SimpleGrantedAuthority("ROLE_USER"))

            mockMvc
                .perform(
                    get("/api/v1/github/config/owner/repo")
                        .with(userJwt),
                ).andExpect(status().isForbidden)
        }
    }

    private fun validConfigBody(): String = objectMapper.writeValueAsString(
        ConfigureRepositoryRequest(
            autoUpdate = true,
            schedule = UpdateSchedule(
                seconds = listOf("0"),
                minutes = listOf("30"),
                hour = listOf("2"),
                dayOfWeek = listOf("*"),
                dayOfMonth = listOf("*"),
                monthOfYear = listOf("*"),
            ),
        ),
    )
}
