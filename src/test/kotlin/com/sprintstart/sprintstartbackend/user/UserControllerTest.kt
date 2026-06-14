package com.sprintstart.sprintstartbackend.user

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.sprintstart.sprintstartbackend.user.controller.UserController
import com.sprintstart.sprintstartbackend.user.external.enums.WorkingArea
import com.sprintstart.sprintstartbackend.user.model.dto.GetUserResponse
import com.sprintstart.sprintstartbackend.user.model.dto.PatchMeRequest
import com.sprintstart.sprintstartbackend.user.model.dto.PatchUserRequest
import com.sprintstart.sprintstartbackend.user.model.dto.PatchUserResponse
import com.sprintstart.sprintstartbackend.user.model.dto.UpdateUserRequest
import com.sprintstart.sprintstartbackend.user.model.dto.UpdateUserResponse
import com.sprintstart.sprintstartbackend.user.service.UserService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@WebMvcTest(UserController::class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    private val objectMapper = jacksonObjectMapper()

    @MockkBean
    private lateinit var userService: UserService

    @Test
    fun `getAllUsers should return 200 and all users`() {
        val response1 = GetUserResponse(
            id = UUID.randomUUID(),
            authId = "auth-1",
            username = "alice",
            email = "alice.dev@mail.de",
            firstname = "Alice",
            lastname = "Developer",
            workingArea = WorkingArea.BACKEND_DEV,
        )
        val response2 = GetUserResponse(
            id = UUID.randomUUID(),
            authId = "auth-2",
            username = "bob",
            email = "bob.front@mail.de",
            firstname = "Bob",
            lastname = "Frontend",
            workingArea = WorkingArea.FRONTEND_DEV,
        )

        every { userService.getAllUsers() } returns listOf(response1, response2)

        mockMvc
            .perform(get("/api/v1/users"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))

        verify(exactly = 1) { userService.getAllUsers() }
    }

    @Test
    fun `getUserById should return 200 and user`() {
        val id = UUID.randomUUID()
        val response = GetUserResponse(
            id = id,
            authId = "auth-1",
            username = "alice",
            email = "alice.dev@mail.de",
            firstname = "Alice",
            lastname = "Developer",
            workingArea = WorkingArea.BACKEND_DEV,
        )

        every { userService.getUserById(id) } returns response

        mockMvc.perform(get("/api/v1/users/$id"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))

        verify(exactly = 1) { userService.getUserById(id) }
    }

    @Test
    fun `getUserById should return 404 when not found`() {
        val id = UUID.randomUUID()

        every { userService.getUserById(id) } throws ResponseStatusException(HttpStatus.NOT_FOUND)

        mockMvc.perform(get("/api/v1/users/$id"))
            .andExpect(status().isNotFound)

        verify(exactly = 1) { userService.getUserById(id) }
    }

    @Test
    fun `updateUserById should return 200 and updated user`() {
        val id = UUID.randomUUID()
        val request = UpdateUserRequest(workingArea = WorkingArea.BACKEND_DEV)
        val response = UpdateUserResponse(
            id = id,
            authId = "auth-1",
            username = "alice",
            email = "alice.dev@mail.de",
            firstname = "Alice",
            lastname = "Developer",
            workingArea = WorkingArea.BACKEND_DEV,
        )

        every { userService.updateUserById(id, request) } returns response

        mockMvc.perform(
            put("/api/v1/users/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))

        verify(exactly = 1) { userService.updateUserById(id, request) }
    }

    @Test
    fun `updateUserById should return 404 when not found`() {
        val id = UUID.randomUUID()
        val request = UpdateUserRequest(workingArea = WorkingArea.BACKEND_DEV)

        every { userService.updateUserById(id, request) } throws ResponseStatusException(HttpStatus.NOT_FOUND)

        mockMvc.perform(
            put("/api/v1/users/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
        )
            .andExpect(status().isNotFound)

        verify(exactly = 1) { userService.updateUserById(id, request) }
    }

    @Test
    fun `patchUserById should return 200 and patched user`() {
        val id = UUID.randomUUID()
        val request = PatchUserRequest(workingArea = WorkingArea.FRONTEND_DEV)
        val response = PatchUserResponse(
            id = id,
            authId = "auth-1",
            username = "alice",
            email = "alice.dev@mail.de",
            firstname = "Alice",
            lastname = "Developer",
            workingArea = WorkingArea.FRONTEND_DEV,
        )

        every { userService.patchUserById(id, request) } returns response

        mockMvc.perform(
            patch("/api/v1/users/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))

        verify(exactly = 1) { userService.patchUserById(id, request) }
    }

    @Test
    fun `patchUserById should return 404 when not found`() {
        val id = UUID.randomUUID()
        val request = PatchUserRequest(workingArea = WorkingArea.FRONTEND_DEV)

        every { userService.patchUserById(id, request) } throws ResponseStatusException(HttpStatus.NOT_FOUND)

        mockMvc.perform(
            patch("/api/v1/users/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
        )
            .andExpect(status().isNotFound)

        verify(exactly = 1) { userService.patchUserById(id, request) }
    }

    @Test
    fun `deleteUserById should return 204`() {
        val id = UUID.randomUUID()

        every { userService.deleteUserById(id) } just Runs

        mockMvc.perform(delete("/api/v1/users/$id"))
            .andExpect(status().isNoContent)

        verify(exactly = 1) { userService.deleteUserById(id) }
    }

    @Test
    fun `deleteUserById should return 404 when not found`() {
        val id = UUID.randomUUID()

        every { userService.deleteUserById(id) } throws ResponseStatusException(HttpStatus.NOT_FOUND)

        mockMvc.perform(delete("/api/v1/users/$id"))
            .andExpect(status().isNotFound)

        verify(exactly = 1) { userService.deleteUserById(id) }
    }
}
