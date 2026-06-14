package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingPath
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingPathRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OnboardingPathServiceTest {

    private val onboardingPathRepository: OnboardingPathRepository = mockk()
    private val userApi: UserApi = mockk()
    private val service = OnboardingPathService(onboardingPathRepository, userApi)

    private val userId = UUID.randomUUID()
    private val pathId = UUID.randomUUID()
    private val authId = "auth|test-user"

    private fun makePath(id: UUID = pathId, uid: UUID = userId) =
        OnboardingPath(id = id, userId = uid)

    @Nested
    inner class CreateOnboardingPathForUser {
        @Test
        fun `creates path when user exists`() {
            val path = makePath()
            every { userApi.exists(userId) } returns true
            every { onboardingPathRepository.save(any()) } returns path

            val result = service.createOnboardingPathForUser(userId)

            assertEquals(path.id, result.id)
            assertEquals(userId, result.userId)
            verify(exactly = 1) { onboardingPathRepository.save(any()) }
        }

        @Test
        fun `throws 404 when user does not exist`() {
            every { userApi.exists(userId) } returns false

            assertThrows<ResponseStatusException> {
                service.createOnboardingPathForUser(userId)
            }.also { assertEquals(404, it.statusCode.value()) }

            verify(exactly = 0) { onboardingPathRepository.save(any()) }
        }
    }

    @Nested
    inner class GetAllOnboardingPathOverviews {
        @Test
        fun `returns mapped list of all paths`() {
            val paths = listOf(makePath(UUID.randomUUID()), makePath(UUID.randomUUID()))
            every { onboardingPathRepository.findAll() } returns paths

            val result = service.getAllOnboardingPathOverviews()

            assertEquals(2, result.size)
        }
    }

    @Nested
    inner class GetOnboardingPathOverviewByUserId {
        @Test
        fun `returns path when user and path exist`() {
            val path = makePath()
            every { userApi.exists(userId) } returns true
            every { onboardingPathRepository.findOnboardingPathByUserId(userId) } returns Optional.of(path)

            val result = service.getOnboardingPathOverviewByUserId(userId)

            assertEquals(path.id, result.id)
        }

        @Test
        fun `throws 404 when user does not exist`() {
            every { userApi.exists(userId) } returns false

            assertThrows<ResponseStatusException> {
                service.getOnboardingPathOverviewByUserId(userId)
            }.also { assertEquals(404, it.statusCode.value()) }
        }

        @Test
        fun `throws 404 when path not found for user`() {
            every { userApi.exists(userId) } returns true
            every { onboardingPathRepository.findOnboardingPathByUserId(userId) } returns Optional.empty()

            assertThrows<ResponseStatusException> {
                service.getOnboardingPathOverviewByUserId(userId)
            }.also { assertEquals(404, it.statusCode.value()) }
        }
    }

    @Nested
    inner class GetOnboardingPathByAuthId {
        @Test
        fun `returns path for authenticated user`() {
            val path = makePath()
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingPathRepository.findOnboardingPathByUserId(userId) } returns Optional.of(path)

            val result = service.getOnboardingPathByAuthId(authId)

            assertEquals(path.id, result.id)
        }

        @Test
        fun `throws 404 when authId not found`() {
            every { userApi.getUserIdByAuthId(authId) } returns Optional.empty()

            assertThrows<ResponseStatusException> {
                service.getOnboardingPathByAuthId(authId)
            }.also { assertEquals(404, it.statusCode.value()) }
        }

        @Test
        fun `throws 404 when no path for resolved user`() {
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingPathRepository.findOnboardingPathByUserId(userId) } returns Optional.empty()

            assertThrows<ResponseStatusException> {
                service.getOnboardingPathByAuthId(authId)
            }.also { assertEquals(404, it.statusCode.value()) }
        }
    }

    @Nested
    inner class DeleteOnboardingPathByUserId {
        @Test
        fun `deletes path when user exists`() {
            every { userApi.exists(userId) } returns true
            every { onboardingPathRepository.deleteByUserId(userId) } just runs

            service.deleteOnboardingPathByUserId(userId)

            verify(exactly = 1) { onboardingPathRepository.deleteByUserId(userId) }
        }

        @Test
        fun `throws 404 when user does not exist`() {
            every { userApi.exists(userId) } returns false

            assertThrows<ResponseStatusException> {
                service.deleteOnboardingPathByUserId(userId)
            }.also { assertEquals(404, it.statusCode.value()) }

            verify(exactly = 0) { onboardingPathRepository.deleteByUserId(any()) }
        }
    }

    @Nested
    inner class DeleteOnboardingPathByAuthId {
        @Test
        fun `deletes path for authenticated user`() {
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingPathRepository.deleteByUserId(userId) } just runs

            service.deleteOnboardingPathByAuthId(authId)

            verify(exactly = 1) { onboardingPathRepository.deleteByUserId(userId) }
        }

        @Test
        fun `throws 404 when authId not found`() {
            every { userApi.getUserIdByAuthId(authId) } returns Optional.empty()

            assertThrows<ResponseStatusException> {
                service.deleteOnboardingPathByAuthId(authId)
            }.also { assertEquals(404, it.statusCode.value()) }
        }
    }

    @Nested
    inner class DeleteOnboardingPathById {
        @Test
        fun `delegates deletion to repository`() {
            every { onboardingPathRepository.deleteById(pathId) } just runs

            service.deleteOnboardingPathById(pathId)

            verify(exactly = 1) { onboardingPathRepository.deleteById(pathId) }
        }
    }
}
