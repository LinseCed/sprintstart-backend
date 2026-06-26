package com.sprintstart.sprintstartbackend.user

import com.sprintstart.sprintstartbackend.user.external.enums.Role
import com.sprintstart.sprintstartbackend.user.external.enums.WorkingArea
import com.sprintstart.sprintstartbackend.user.model.dto.KeycloakEventRequest
import com.sprintstart.sprintstartbackend.user.model.entity.User
import com.sprintstart.sprintstartbackend.user.repository.UserRepository
import com.sprintstart.sprintstartbackend.user.service.KeycloakEventService
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class KeycloakEventServiceTest {
    private val userRepository: UserRepository = mockk(relaxed = true)
    private val service = KeycloakEventService(userRepository)

    @Test
    fun `realm role delete removes mapped local permission group`() {
        val user = User(
            authId = "auth-1",
            username = "alice",
            email = "alice@mail.de",
            firstname = "Alice",
            lastname = "Developer",
            workingArea = WorkingArea.BACKEND_DEV,
        )
        user.roles.addAll(setOf(Role.USER, Role.ADMIN))
        every { userRepository.findByAuthId("auth-1") } returns Optional.of(user)

        service.handleEvent(
            KeycloakEventRequest(
                source = "keycloak",
                resourceType = "REALM_ROLE_MAPPING",
                eventType = "DELETE",
                realmId = "sprintstart",
                authId = "auth-1",
                username = null,
                email = null,
                firstName = null,
                lastName = null,
                realmRoles = setOf("admin"),
            ),
        )

        assertThat(user.roles).containsExactly(Role.USER)
    }
}
