package com.sprintstart.sprintstartbackend.onboarding.controller

import com.ninjasquad.springmockk.MockkBean
import com.sprintstart.sprintstartbackend.config.SecurityConfig
import com.sprintstart.sprintstartbackend.onboarding.external.enums.BoardCardKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.BoardCardOwner
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.BoardCardResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.BoardMomentKey
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.BoardMomentResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.BoardResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.BoardVocabularyResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.PathToFirstContributionContent
import com.sprintstart.sprintstartbackend.onboarding.service.BoardService
import com.sprintstart.sprintstartbackend.user.external.UserApi
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Optional
import java.util.UUID

@WebMvcTest(BoardController::class)
@Import(SecurityConfig::class)
@AutoConfigureMockMvc
class BoardControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockkBean
    private lateinit var boardService: BoardService

    @MockkBean
    private lateinit var userApi: UserApi

    @MockkBean
    private lateinit var jwtDecoder: JwtDecoder

    private val authId = "test-auth-id"
    private val userId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()

    private fun jwtWithSubject(subject: String, vararg roles: String): JwtRequestPostProcessor =
        jwt()
            .jwt { jwt ->
                jwt.subject(subject)
                jwt.claim("realm_access", mapOf("roles" to roles.toList()))
            }.authorities(roles.map { SimpleGrantedAuthority("ROLE_$it") })

    private val userJwt = jwtWithSubject(authId, "USER")

    private fun board() = BoardResponse(
        boardId = UUID.randomUUID(),
        projectId = projectId,
        vocabulary = BoardVocabularyResponse(
            trackLabel = "Scrum Master",
            contributionNoun = "ceremony",
            contributionNounPlural = "ceremonies",
            contributionVerbPast = "facilitated",
        ),
        cards = listOf(
            BoardCardResponse(
                id = UUID.randomUUID(),
                kind = BoardCardKind.PATH_TO_FIRST_CONTRIBUTION,
                owner = BoardCardOwner.AI,
                position = 0,
                placedAt = null,
                content = PathToFirstContributionContent(
                    moments = listOf(BoardMomentResponse(BoardMomentKey.JOINED, null)),
                    acceptedCount = 0,
                    autonomyReachedAt = null,
                    stalledReason = null,
                ),
            ),
        ),
    )

    @Test
    fun `getMyBoard returns the caller's board`() {
        every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
        every { boardService.getBoard(userId, projectId) } returns board()

        mockMvc
            .perform(get("/api/v1/onboarding/me/board").param("projectId", projectId.toString()).with(userJwt))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.vocabulary.contributionNoun").value("ceremony"))
            // The card's kind must survive onto the wire as the content's discriminator, or a
            // client cannot tell which card it is rendering.
            .andExpect(jsonPath("$.cards[0].content.kind").value("PATH_TO_FIRST_CONTRIBUTION"))
            .andExpect(jsonPath("$.cards[0].content.moments[0].key").value("JOINED"))
            .andExpect(jsonPath("$.cards[0].content.moments[0].reachedAt").doesNotExist())
    }

    @Test
    fun `getMyBoard is 404 for a project the caller is not on`() {
        every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
        every { boardService.getBoard(userId, projectId) } returns null

        mockMvc
            .perform(get("/api/v1/onboarding/me/board").param("projectId", projectId.toString()).with(userJwt))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `dismissCard removes a card from the caller's own board`() {
        val cardId = UUID.randomUUID()
        every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
        every { boardService.dismiss(userId, cardId) } returns true

        mockMvc
            .perform(delete("/api/v1/onboarding/me/board/cards/$cardId").with(userJwt))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `dismissCard is 404 for a card that is not on a board of theirs`() {
        val cardId = UUID.randomUUID()
        every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
        every { boardService.dismiss(userId, cardId) } returns false

        // Same answer as a card that does not exist: a 403 would confirm the id is somebody's card.
        mockMvc
            .perform(delete("/api/v1/onboarding/me/board/cards/$cardId").with(userJwt))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `dismissCard requires authentication`() {
        mockMvc
            .perform(delete("/api/v1/onboarding/me/board/cards/${UUID.randomUUID()}"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `getMyBoard requires authentication`() {
        mockMvc
            .perform(get("/api/v1/onboarding/me/board").param("projectId", projectId.toString()))
            .andExpect(status().isUnauthorized)
    }
}
