package com.sprintstart.sprintstartbackend.user

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.sprintstart.sprintstartbackend.user.controller.UserController
import com.sprintstart.sprintstartbackend.user.external.enums.WorkingArea
import com.sprintstart.sprintstartbackend.user.model.dto.CreateUserRequest
import com.sprintstart.sprintstartbackend.user.model.dto.CreateUserResponse
import com.sprintstart.sprintstartbackend.user.model.dto.GetUserResponse
import com.sprintstart.sprintstartbackend.user.model.dto.PatchUserRequest
import com.sprintstart.sprintstartbackend.user.model.dto.PatchUserResponse
import com.sprintstart.sprintstartbackend.user.model.dto.SyncUserRequest
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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
    fun `createUser should return 201 and created user`() {
        val request = CreateUserRequest(
            authId = "keycloak-id-1",
            username = "max_backend",
            firstname = "Max",
            lastname = "Backend",
            workingArea = WorkingArea.BACKEND_DEV,
        )
        val response = GetUserFixtures.createResponse(
            id = UUID.randomUUID(),
            authId = request.authId,
            username = request.username,
            firstname = request.firstname,
            lastname = request.lastname,
            workingArea = request.workingArea,
        )

        every {
            userService.createUser(request)
        } returns CreateUserResponse(
            id = response.id,
            authId = response.authId,
            username = response.username,
            firstname = response.firstname,
            lastname = response.lastname,
            workingArea = response.workingArea,
        )

        mockMvc.perform(
            post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
        )
            .andExpect(status().isCreated)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").value(response.id.toString()))
            .andExpect(jsonPath("$.authId").value(request.authId))
            .andExpect(jsonPath("$.username").value(request.username))
            .andExpect(jsonPath("$.firstname").value(request.firstname))
            .andExpect(jsonPath("$.lastname").value(request.lastname))
            .andExpect(jsonPath("$.workingArea").value("BACKEND_DEV"))

        verify(exactly = 1) {
            userService.createUser(request)
        }
    }

    @Test
    fun `createUser should return 400 on invalid body`() {
        val invalidRequest = CreateUserRequest(
            authId = "",
            username = "",
            firstname = "Max",
            lastname = "",
            workingArea = WorkingArea.BACKEND_DEV,
        )

        mockMvc.perform(
            post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest)),
        )
            .andExpect(status().isBadRequest)

        verify(exactly = 0) {
            userService.createUser(any())
        }
    }

    @Test
    fun `getAllUsers should return 200 and list of users`() {
        val users = listOf(
            GetUserFixtures.getResponse(
                authId = "keycloak-id-1",
                username = "max_backend",
                firstname = "Max",
                lastname = "Backend",
                workingArea = WorkingArea.BACKEND_DEV,
            ),
            GetUserFixtures.getResponse(
                authId = "keycloak-id-2",
                username = "anna_frontend",
                firstname = "Anna",
                lastname = "Frontend",
                workingArea = WorkingArea.FRONTEND_DEV,
            ),
        )

        every {
            userService.getAllUsers()
        } returns users

        mockMvc.perform(get("/api/v1/users"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].authId").value("keycloak-id-1"))
            .andExpect(jsonPath("$[0].workingArea").value("BACKEND_DEV"))
            .andExpect(jsonPath("$[1].authId").value("keycloak-id-2"))
            .andExpect(jsonPath("$[1].workingArea").value("FRONTEND_DEV"))

        verify(exactly = 1) {
            userService.getAllUsers()
        }
    }

    @Test
    fun `getUserByAuthId should return 200 and user`() {
        val authId = "keycloak-id-1"
        val user = GetUserFixtures.getResponse(
            authId = authId,
            username = "max_backend",
            firstname = "Max",
            lastname = "Backend",
            workingArea = WorkingArea.BACKEND_DEV,
        )

        every {
            userService.getUserByAuthId(authId)
        } returns user

        mockMvc.perform(get("/api/v1/users/{authId}", authId))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.authId").value(authId))
            .andExpect(jsonPath("$.username").value("max_backend"))

        verify(exactly = 1) {
            userService.getUserByAuthId(authId)
        }
    }

    @Test
    fun `updateUserByAuthId should return 200 and updated user`() {
        val authId = "keycloak-id-1"
        val request = UpdateUserRequest(
            username = "max_backend_updated",
            firstname = "Max",
            lastname = "Backend",
            workingArea = WorkingArea.BACKEND_DEV,
        )
        val response = UpdateUserResponse(
            id = UUID.randomUUID(),
            authId = authId,
            username = request.username,
            firstname = request.firstname,
            lastname = request.lastname,
            workingArea = request.workingArea,
        )

        every {
            userService.updateUserByAuthId(authId, request)
        } returns response

        mockMvc.perform(
            put("/api/v1/users/{authId}", authId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authId").value(authId))
            .andExpect(jsonPath("$.username").value("max_backend_updated"))

        verify(exactly = 1) {
            userService.updateUserByAuthId(authId, request)
        }
    }

    @Test
    fun `patchUserByAuthId should return 200 and patched user`() {
        val authId = "keycloak-id-1"
        val request = PatchUserRequest(
            username = "max_backend_updated",
            firstname = "Max",
        )
        val response = PatchUserResponse(
            id = UUID.randomUUID(),
            authId = authId,
            username = "max_backend_updated",
            firstname = "Max",
            lastname = "Backend",
            workingArea = WorkingArea.BACKEND_DEV,
        )

        every {
            userService.patchUserByAuthId(authId, request)
        } returns response

        mockMvc.perform(
            patch("/api/v1/users/{authId}", authId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authId").value(authId))
            .andExpect(jsonPath("$.username").value("max_backend_updated"))

        verify(exactly = 1) {
            userService.patchUserByAuthId(authId, request)
        }
    }

    @Test
    fun `syncUser should return 200 and synced user`() {
        val request = SyncUserRequest(
            authId = "keycloak-id-1",
            username = "max_backend",
            firstname = "Max",
            lastname = "Backend",
        )
        val response = GetUserFixtures.getResponse(
            authId = request.authId,
            username = request.username,
            firstname = request.firstname,
            lastname = request.lastname,
            workingArea = WorkingArea.NO_WORKING_AREA,
        )

        every {
            userService.syncUser(request)
        } returns response

        mockMvc.perform(
            post("/api/v1/users/sync")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authId").value(request.authId))
            .andExpect(jsonPath("$.workingArea").value("NO_WORKING_AREA"))

        verify(exactly = 1) {
            userService.syncUser(request)
        }
    }

    @Test
    fun `deleteUserByAuthId should return 204`() {
        val authId = "keycloak-id-1"

        every {
            userService.deleteUserByAuthId(authId)
        } just Runs

        mockMvc.perform(delete("/api/v1/users/{authId}", authId))
            .andExpect(status().isNoContent)
            .andExpect(content().string(""))

        verify(exactly = 1) {
            userService.deleteUserByAuthId(authId)
        }
    }
}

private object GetUserFixtures {
    fun createResponse(
        id: UUID,
        authId: String,
        username: String,
        firstname: String,
        lastname: String,
        workingArea: WorkingArea,
    ): GetUserResponse {
        return GetUserResponse(
            id = id,
            authId = authId,
            username = username,
            firstname = firstname,
            lastname = lastname,
            workingArea = workingArea,
        )
    }

    fun getResponse(
        id: UUID = UUID.randomUUID(),
        authId: String,
        username: String,
        firstname: String,
        lastname: String,
        workingArea: WorkingArea,
    ): GetUserResponse {
        return GetUserResponse(
            id = id,
            authId = authId,
            username = username,
            firstname = firstname,
            lastname = lastname,
            workingArea = workingArea,
        )
    }
}
