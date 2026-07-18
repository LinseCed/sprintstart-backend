package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.connectors.github.external.GithubRepositoryApi
import com.sprintstart.sprintstartbackend.connectors.github.external.dto.PullRequestEvidence
import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencySource
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.NodeState
import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepType
import com.sprintstart.sprintstartbackend.onboarding.external.enums.VerificationType
import com.sprintstart.sprintstartbackend.onboarding.external.model.GradeResult
import com.sprintstart.sprintstartbackend.onboarding.external.model.LessonContentSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.LessonOutcome
import com.sprintstart.sprintstartbackend.onboarding.external.model.LessonProvenanceSchema
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingPath
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingPhase
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingStep
import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserCompetencyState
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Verification
import com.sprintstart.sprintstartbackend.onboarding.model.entity.VerificationAttempt
import com.sprintstart.sprintstartbackend.onboarding.model.exceptions.OnboardingAiException
import com.sprintstart.sprintstartbackend.onboarding.model.request.verification.SubmitVerificationAttemptRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.verification.UpsertVerificationRequest
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingStepRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserCompetencyStateRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.VerificationAttemptRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.VerificationRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerificationServiceTest {
    private val verificationRepository: VerificationRepository = mockk()
    private val verificationAttemptRepository: VerificationAttemptRepository = mockk()
    private val onboardingStepRepository: OnboardingStepRepository = mockk()
    private val competencyRepository: CompetencyRepository = mockk()
    private val userCompetencyStateRepository: UserCompetencyStateRepository = mockk()
    private val competencyGraphVersionService: CompetencyGraphVersionService = mockk()
    private val onboardingAiClient: OnboardingAiClient = mockk()
    private val githubRepositoryApi: GithubRepositoryApi = mockk()
    private val userApi: UserApi = mockk()
    private val transactionManager: PlatformTransactionManager = mockk(relaxed = true)
    private val service = VerificationService(
        verificationRepository,
        verificationAttemptRepository,
        onboardingStepRepository,
        competencyRepository,
        userCompetencyStateRepository,
        competencyGraphVersionService,
        onboardingAiClient,
        githubRepositoryApi,
        userApi,
        transactionManager,
    )

    private val userId = UUID.randomUUID()
    private val authId = "auth|test-user"

    private fun makeStep(
        status: StepStatus = StepStatus.WAITING,
        content: String? = null,
    ): OnboardingStep {
        val path = OnboardingPath(userId = userId)
        val phase = OnboardingPhase(path = path, position = 0, title = "P", description = "d")
        return OnboardingStep(
            phase = phase,
            position = 0,
            title = "Learn Kotlin",
            description = "d",
            type = StepType.DOCUMENT,
            estimatedMinutes = 10,
            expectedOutcome = "e",
            status = status,
            content = content,
        )
    }

    private fun makeVerification(
        type: VerificationType,
        stepId: UUID,
        rubric: String? = null,
        canonicalAnswer: String? = null,
        repositoryConnectionId: UUID? = null,
        competencyKey: String = "kotlin",
        level: String = "beginner",
    ) = Verification(
        stepId = stepId,
        type = type,
        prompt = "Explain it",
        rubric = rubric,
        canonicalAnswer = canonicalAnswer,
        repositoryConnectionId = repositoryConnectionId,
        competencyKey = competencyKey,
        level = level,
    )

    @Nested
    inner class SubmitAttemptForMe {
        @Test
        fun `EXACT pass writes VERIFIED ledger entry and finishes the step without calling the AI`() {
            val step = makeStep()
            val verification = makeVerification(VerificationType.EXACT, step.id, canonicalAnswer = "Chroma")
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingStepRepository.findByIdAndPhasePathUserId(step.id, userId) } returns Optional.of(step)
            every { verificationRepository.findByStepId(step.id) } returns verification
            every { verificationAttemptRepository.countByVerificationIdAndUserId(verification.id, userId) } returns 0
            every { competencyGraphVersionService.currentVersion() } returns 3
            every { userCompetencyStateRepository.findByUserIdAndCompetencyKey(userId, "kotlin") } returns null
            val savedState = slot<UserCompetencyState>()
            every { userCompetencyStateRepository.save(capture(savedState)) } answers { savedState.captured }
            every { verificationAttemptRepository.save(any()) } answers { firstArg() }

            val result = service.submitAttemptForMe(authId, step.id, SubmitVerificationAttemptRequest("chroma"))

            assertTrue(result.passed)
            assertEquals(StepStatus.FINISHED, step.status)
            assertEquals(CompetencySource.VERIFIED, savedState.captured.source)
            assertEquals(1, savedState.captured.level)
            assertEquals(3, result.graphVersion)
            coVerify(exactly = 0) { onboardingAiClient.gradeKnowledge(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `EXACT fail logs an attempt with a hint and leaves the step unfinished`() {
            val step = makeStep(status = StepStatus.IN_PROGRESS)
            val verification = makeVerification(VerificationType.EXACT, step.id, canonicalAnswer = "Chroma")
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingStepRepository.findByIdAndPhasePathUserId(step.id, userId) } returns Optional.of(step)
            every { verificationRepository.findByStepId(step.id) } returns verification
            every { verificationAttemptRepository.countByVerificationIdAndUserId(verification.id, userId) } returns 0
            every { competencyGraphVersionService.currentVersion() } returns 1
            val savedAttempt = slot<VerificationAttempt>()
            every { verificationAttemptRepository.save(capture(savedAttempt)) } answers { savedAttempt.captured }

            val result = service.submitAttemptForMe(authId, step.id, SubmitVerificationAttemptRequest("pinecone"))

            assertFalse(result.passed)
            assertEquals(StepStatus.IN_PROGRESS, step.status)
            assertFalse(savedAttempt.captured.passed)
            assertThat(savedAttempt.captured.hint).isNotNull()
            verify(exactly = 0) { userCompetencyStateRepository.save(any()) }
        }

        @Test
        fun `test-out - passing on a never-started step finishes it directly`() {
            val step = makeStep(status = StepStatus.WAITING)
            assertNull(step.startedAt)
            val verification = makeVerification(VerificationType.ATTEST, step.id)
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingStepRepository.findByIdAndPhasePathUserId(step.id, userId) } returns Optional.of(step)
            every { verificationRepository.findByStepId(step.id) } returns verification
            every { verificationAttemptRepository.countByVerificationIdAndUserId(verification.id, userId) } returns 0
            every { competencyGraphVersionService.currentVersion() } returns 1
            every { userCompetencyStateRepository.findByUserIdAndCompetencyKey(userId, "kotlin") } returns null
            every { userCompetencyStateRepository.save(any()) } answers { firstArg() }
            every { verificationAttemptRepository.save(any()) } answers { firstArg() }

            val result = service.submitAttemptForMe(authId, step.id, SubmitVerificationAttemptRequest("I understand"))

            assertTrue(result.passed)
            assertEquals(StepStatus.FINISHED, step.status)
            assertThat(step.startedAt).isNotNull()
            assertThat(step.completedAt).isNotNull()
        }

        @Test
        fun `KNOWLEDGE delegates grading to the AI client`() {
            val step = makeStep(content = "Kotlin is null-safe.")
            val verification = makeVerification(VerificationType.KNOWLEDGE, step.id, rubric = "mentions null-safety")
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingStepRepository.findByIdAndPhasePathUserId(step.id, userId) } returns Optional.of(step)
            every { verificationRepository.findByStepId(step.id) } returns verification
            every { verificationAttemptRepository.countByVerificationIdAndUserId(verification.id, userId) } returns 0
            every { competencyGraphVersionService.currentVersion() } returns 1
            coEvery {
                onboardingAiClient.gradeKnowledge(
                    "Explain it",
                    "mentions null-safety",
                    "Kotlin is null-safe.",
                    "ans",
                    1,
                )
            } returns GradeResult(passed = true, score = 0.9, feedback = "Good.")
            every { userCompetencyStateRepository.findByUserIdAndCompetencyKey(userId, "kotlin") } returns null
            every { userCompetencyStateRepository.save(any()) } answers { firstArg() }
            every { verificationAttemptRepository.save(any()) } answers { firstArg() }

            val result = service.submitAttemptForMe(authId, step.id, SubmitVerificationAttemptRequest("ans"))

            assertTrue(result.passed)
            coVerify(exactly = 1) { onboardingAiClient.gradeKnowledge(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `KNOWLEDGE surfaces 503 when the AI service is unavailable, without persisting an attempt`() {
            val step = makeStep()
            val verification = makeVerification(VerificationType.KNOWLEDGE, step.id, rubric = "mentions null-safety")
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingStepRepository.findByIdAndPhasePathUserId(step.id, userId) } returns Optional.of(step)
            every { verificationRepository.findByStepId(step.id) } returns verification
            every { verificationAttemptRepository.countByVerificationIdAndUserId(verification.id, userId) } returns 0
            every { competencyGraphVersionService.currentVersion() } returns 1
            coEvery { onboardingAiClient.gradeKnowledge(any(), any(), any(), any(), any()) } throws
                OnboardingAiException(503, "", "AI unavailable")

            assertThrows<ResponseStatusException> {
                service.submitAttemptForMe(authId, step.id, SubmitVerificationAttemptRequest("ans"))
            }.also { assertEquals(503, it.statusCode.value()) }

            verify(exactly = 0) { verificationAttemptRepository.save(any()) }
        }

        @Test
        fun `ARTIFACT fetches PR evidence and delegates grading to the AI client`() {
            val repositoryConnectionId = UUID.randomUUID()
            val step = makeStep()
            val verification = makeVerification(
                VerificationType.ARTIFACT,
                step.id,
                rubric = "closes the ticket",
                repositoryConnectionId = repositoryConnectionId,
            )
            val evidence = PullRequestEvidence(
                title = "Fix bug",
                body = "Closes #42",
                state = "MERGED",
                filesChanged = listOf("src/Main.kt"),
                checksPassed = true,
                commitMessages = listOf("fix: bug"),
            )
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingStepRepository.findByIdAndPhasePathUserId(step.id, userId) } returns Optional.of(step)
            every { verificationRepository.findByStepId(step.id) } returns verification
            every { verificationAttemptRepository.countByVerificationIdAndUserId(verification.id, userId) } returns 0
            every { competencyGraphVersionService.currentVersion() } returns 1
            coEvery { githubRepositoryApi.getPullRequestEvidence(repositoryConnectionId, 42) } returns evidence
            coEvery { onboardingAiClient.gradeArtifact("Explain it", "closes the ticket", any()) } returns
                GradeResult(passed = true, score = 1.0, feedback = "Satisfies the rubric.")
            every { userCompetencyStateRepository.findByUserIdAndCompetencyKey(userId, "kotlin") } returns null
            every { userCompetencyStateRepository.save(any()) } answers { firstArg() }
            every { verificationAttemptRepository.save(any()) } answers { firstArg() }

            val result = service.submitAttemptForMe(authId, step.id, SubmitVerificationAttemptRequest("42"))

            assertTrue(result.passed)
            coVerify(exactly = 1) { githubRepositoryApi.getPullRequestEvidence(repositoryConnectionId, 42) }
            coVerify(exactly = 1) { onboardingAiClient.gradeArtifact(any(), any(), any()) }
        }

        @Test
        fun `ARTIFACT fails locally without a GitHub or AI call when the answer isn't a PR number`() {
            val step = makeStep()
            val verification = makeVerification(
                VerificationType.ARTIFACT,
                step.id,
                rubric = "closes the ticket",
                repositoryConnectionId = UUID.randomUUID(),
            )
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingStepRepository.findByIdAndPhasePathUserId(step.id, userId) } returns Optional.of(step)
            every { verificationRepository.findByStepId(step.id) } returns verification
            every { verificationAttemptRepository.countByVerificationIdAndUserId(verification.id, userId) } returns 0
            every { competencyGraphVersionService.currentVersion() } returns 1
            every { verificationAttemptRepository.save(any()) } answers { firstArg() }

            val result = service.submitAttemptForMe(authId, step.id, SubmitVerificationAttemptRequest("not a number"))

            assertFalse(result.passed)
            coVerify(exactly = 0) { githubRepositoryApi.getPullRequestEvidence(any(), any()) }
            coVerify(exactly = 0) { onboardingAiClient.gradeArtifact(any(), any(), any()) }
        }

        @Test
        fun `ARTIFACT fails locally without an AI call when the PR isn't found`() {
            val repositoryConnectionId = UUID.randomUUID()
            val step = makeStep()
            val verification = makeVerification(
                VerificationType.ARTIFACT,
                step.id,
                rubric = "closes the ticket",
                repositoryConnectionId = repositoryConnectionId,
            )
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingStepRepository.findByIdAndPhasePathUserId(step.id, userId) } returns Optional.of(step)
            every { verificationRepository.findByStepId(step.id) } returns verification
            every { verificationAttemptRepository.countByVerificationIdAndUserId(verification.id, userId) } returns 0
            every { competencyGraphVersionService.currentVersion() } returns 1
            coEvery { githubRepositoryApi.getPullRequestEvidence(repositoryConnectionId, 99) } returns null
            every { verificationAttemptRepository.save(any()) } answers { firstArg() }

            val result = service.submitAttemptForMe(authId, step.id, SubmitVerificationAttemptRequest("99"))

            assertFalse(result.passed)
            coVerify(exactly = 0) { onboardingAiClient.gradeArtifact(any(), any(), any()) }
        }

        @Test
        fun `ARTIFACT throws 500 when repositoryConnectionId isn't configured`() {
            val step = makeStep()
            val verification = makeVerification(VerificationType.ARTIFACT, step.id, rubric = "closes the ticket")
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingStepRepository.findByIdAndPhasePathUserId(step.id, userId) } returns Optional.of(step)
            every { verificationRepository.findByStepId(step.id) } returns verification
            every { verificationAttemptRepository.countByVerificationIdAndUserId(verification.id, userId) } returns 0

            assertThrows<ResponseStatusException> {
                service.submitAttemptForMe(authId, step.id, SubmitVerificationAttemptRequest("1"))
            }.also { assertEquals(500, it.statusCode.value()) }
        }

        @Test
        fun `ARTIFACT surfaces 503 when the AI service is unavailable, without persisting an attempt`() {
            val repositoryConnectionId = UUID.randomUUID()
            val step = makeStep()
            val verification = makeVerification(
                VerificationType.ARTIFACT,
                step.id,
                rubric = "closes the ticket",
                repositoryConnectionId = repositoryConnectionId,
            )
            val evidence = PullRequestEvidence(
                title = "Fix bug",
                body = "",
                state = "OPEN",
                filesChanged = emptyList(),
                checksPassed = null,
                commitMessages = emptyList(),
            )
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingStepRepository.findByIdAndPhasePathUserId(step.id, userId) } returns Optional.of(step)
            every { verificationRepository.findByStepId(step.id) } returns verification
            every { verificationAttemptRepository.countByVerificationIdAndUserId(verification.id, userId) } returns 0
            coEvery { githubRepositoryApi.getPullRequestEvidence(repositoryConnectionId, 7) } returns evidence
            coEvery { onboardingAiClient.gradeArtifact(any(), any(), any()) } throws
                OnboardingAiException(503, "", "AI unavailable")

            assertThrows<ResponseStatusException> {
                service.submitAttemptForMe(authId, step.id, SubmitVerificationAttemptRequest("7"))
            }.also { assertEquals(503, it.statusCode.value()) }

            verify(exactly = 0) { verificationAttemptRepository.save(any()) }
        }

        @Test
        fun `throws 400 when the step is already finished`() {
            val step = makeStep(status = StepStatus.FINISHED)
            val verification = makeVerification(VerificationType.ATTEST, step.id)
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingStepRepository.findByIdAndPhasePathUserId(step.id, userId) } returns Optional.of(step)
            every { verificationRepository.findByStepId(step.id) } returns verification

            assertThrows<ResponseStatusException> {
                service.submitAttemptForMe(authId, step.id, SubmitVerificationAttemptRequest("x"))
            }.also { assertEquals(400, it.statusCode.value()) }
        }

        @Test
        fun `throws 404 when the step has no verification configured`() {
            val step = makeStep()
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingStepRepository.findByIdAndPhasePathUserId(step.id, userId) } returns Optional.of(step)
            every { verificationRepository.findByStepId(step.id) } returns null

            assertThrows<ResponseStatusException> {
                service.submitAttemptForMe(authId, step.id, SubmitVerificationAttemptRequest("x"))
            }.also { assertEquals(404, it.statusCode.value()) }
        }

        @Test
        fun `attemptNo increments across repeated submissions`() {
            val step = makeStep()
            val verification = makeVerification(VerificationType.ATTEST, step.id)
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingStepRepository.findByIdAndPhasePathUserId(step.id, userId) } returns Optional.of(step)
            every { verificationRepository.findByStepId(step.id) } returns verification
            every { verificationAttemptRepository.countByVerificationIdAndUserId(verification.id, userId) } returns 2
            every { competencyGraphVersionService.currentVersion() } returns 1
            every { userCompetencyStateRepository.findByUserIdAndCompetencyKey(userId, "kotlin") } returns null
            every { userCompetencyStateRepository.save(any()) } answers { firstArg() }
            every { verificationAttemptRepository.save(any()) } answers { firstArg() }

            val result = service.submitAttemptForMe(authId, step.id, SubmitVerificationAttemptRequest("x"))

            assertEquals(3, result.attemptNo)
        }

        @Test
        fun `passing unlocks a dependent competency on the graph`() {
            // Regression coverage for "pass writes VERIFIED + unlocks dependents": feed the exact
            // ledger state this service writes into the real (pure) PathProjectionService and
            // confirm the dependent flips from LOCKED to AVAILABLE.
            val step = makeStep()
            val verification = makeVerification(VerificationType.ATTEST, step.id, competencyKey = "kotlin")
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingStepRepository.findByIdAndPhasePathUserId(step.id, userId) } returns Optional.of(step)
            every { verificationRepository.findByStepId(step.id) } returns verification
            every { verificationAttemptRepository.countByVerificationIdAndUserId(verification.id, userId) } returns 0
            every { competencyGraphVersionService.currentVersion() } returns 1
            every { userCompetencyStateRepository.findByUserIdAndCompetencyKey(userId, "kotlin") } returns null
            val savedState = slot<UserCompetencyState>()
            every { userCompetencyStateRepository.save(capture(savedState)) } answers { savedState.captured }
            every { verificationAttemptRepository.save(any()) } answers { firstArg() }

            service.submitAttemptForMe(authId, step.id, SubmitVerificationAttemptRequest("done"))

            val kotlin = Competency(key = "kotlin", label = "Kotlin", kind = CompetencyKind.SKILL)
            val domainModel = Competency(key = "domain-model", label = "Domain model", kind = CompetencyKind.CONCEPT)
            val edge = CompetencyEdge(fromKey = "kotlin", toKey = "domain-model", kind = EdgeKind.PREREQUISITE)
            val projection = PathProjectionService(GraphTraversalService()).project(
                competencies = listOf(kotlin, domainModel),
                edges = listOf(edge),
                targetKeys = setOf("kotlin", "domain-model"),
                ledger = mapOf("kotlin" to savedState.captured.level),
                graphVersion = 1,
            )

            assertEquals(NodeState.MASTERED, projection.nodes.first { it.key == "kotlin" }.state)
            assertEquals(NodeState.AVAILABLE, projection.nodes.first { it.key == "domain-model" }.state)
        }
    }

    @Nested
    inner class UpsertVerification {
        @Test
        fun `creates a verification when none exists`() {
            val step = makeStep()
            every { onboardingStepRepository.findById(step.id) } returns Optional.of(step)
            every { competencyRepository.existsByKey("kotlin") } returns true
            every { verificationRepository.findByStepId(step.id) } returns null
            every { verificationRepository.save(any()) } answers { firstArg() }

            val request = UpsertVerificationRequest(
                type = VerificationType.EXACT,
                prompt = "What DB?",
                canonicalAnswer = "Chroma",
                competencyKey = "kotlin",
                level = "beginner",
            )
            val result = service.upsertVerification(step.id, request)

            assertEquals(VerificationType.EXACT, result.type)
            assertEquals("kotlin", result.competencyKey)
        }

        @Test
        fun `throws 400 when KNOWLEDGE has no rubric`() {
            val step = makeStep()
            every { onboardingStepRepository.findById(step.id) } returns Optional.of(step)

            val request = UpsertVerificationRequest(
                type = VerificationType.KNOWLEDGE,
                prompt = "Why?",
                competencyKey = "kotlin",
                level = "beginner",
            )

            assertThrows<ResponseStatusException> {
                service.upsertVerification(step.id, request)
            }.also { assertEquals(400, it.statusCode.value()) }
        }

        @Test
        fun `throws 400 when EXACT has no canonicalAnswer`() {
            val step = makeStep()
            every { onboardingStepRepository.findById(step.id) } returns Optional.of(step)

            val request = UpsertVerificationRequest(
                type = VerificationType.EXACT,
                prompt = "What?",
                competencyKey = "kotlin",
                level = "beginner",
            )

            assertThrows<ResponseStatusException> {
                service.upsertVerification(step.id, request)
            }.also { assertEquals(400, it.statusCode.value()) }
        }

        @Test
        fun `throws 404 when the referenced competency does not exist`() {
            val step = makeStep()
            every { onboardingStepRepository.findById(step.id) } returns Optional.of(step)
            every { competencyRepository.existsByKey("nope") } returns false

            val request = UpsertVerificationRequest(
                type = VerificationType.ATTEST,
                prompt = "Confirm?",
                competencyKey = "nope",
                level = "beginner",
            )

            assertThrows<ResponseStatusException> {
                service.upsertVerification(step.id, request)
            }.also { assertEquals(404, it.statusCode.value()) }
        }

        @Test
        fun `throws 400 when ARTIFACT has no rubric`() {
            val step = makeStep()
            every { onboardingStepRepository.findById(step.id) } returns Optional.of(step)

            val request = UpsertVerificationRequest(
                type = VerificationType.ARTIFACT,
                prompt = "Ship it",
                repositoryConnectionId = UUID.randomUUID(),
                competencyKey = "kotlin",
                level = "beginner",
            )

            assertThrows<ResponseStatusException> {
                service.upsertVerification(step.id, request)
            }.also { assertEquals(400, it.statusCode.value()) }
        }

        @Test
        fun `throws 400 when ARTIFACT has no repositoryConnectionId`() {
            val step = makeStep()
            every { onboardingStepRepository.findById(step.id) } returns Optional.of(step)

            val request = UpsertVerificationRequest(
                type = VerificationType.ARTIFACT,
                prompt = "Ship it",
                rubric = "closes the ticket",
                competencyKey = "kotlin",
                level = "beginner",
            )

            assertThrows<ResponseStatusException> {
                service.upsertVerification(step.id, request)
            }.also { assertEquals(400, it.statusCode.value()) }
        }

        @Test
        fun `creates an ARTIFACT verification with a repositoryConnectionId`() {
            val step = makeStep()
            val repositoryConnectionId = UUID.randomUUID()
            every { onboardingStepRepository.findById(step.id) } returns Optional.of(step)
            every { competencyRepository.existsByKey("kotlin") } returns true
            every { verificationRepository.findByStepId(step.id) } returns null
            val saved = slot<Verification>()
            every { verificationRepository.save(capture(saved)) } answers { saved.captured }

            val request = UpsertVerificationRequest(
                type = VerificationType.ARTIFACT,
                prompt = "Ship it",
                rubric = "closes the ticket",
                repositoryConnectionId = repositoryConnectionId,
                competencyKey = "kotlin",
                level = "beginner",
            )
            val result = service.upsertVerification(step.id, request)

            assertEquals(VerificationType.ARTIFACT, result.type)
            assertEquals(repositoryConnectionId, saved.captured.repositoryConnectionId)
        }
    }

    @Nested
    inner class SynthesizeContent {
        @Test
        fun `persists content and lesson fingerprint on a synthesized outcome`() = runTest {
            val step = makeStep()
            val verification = makeVerification(VerificationType.KNOWLEDGE, step.id, rubric = "r")
            val competency = Competency(key = "kotlin", label = "Kotlin", kind = CompetencyKind.SKILL)
            every { onboardingStepRepository.findById(step.id) } returns Optional.of(step)
            every { verificationRepository.findByStepId(step.id) } returns verification
            every { competencyRepository.findByKey("kotlin") } returns competency
            coEvery {
                onboardingAiClient.synthesizeLesson("kotlin", "Kotlin", "", "beginner", null)
            } returns LessonOutcome(
                status = "synthesized",
                lesson = LessonContentSchema(
                    competencyKey = "kotlin",
                    level = "beginner",
                    title = "t",
                    body = "grounded body",
                ),
                provenance = LessonProvenanceSchema(corpusFingerprint = "fp-1"),
            )

            service.synthesizeContent(step.id)

            assertEquals("grounded body", step.content)
            assertEquals("fp-1", step.lessonFingerprint)
        }

        @Test
        fun `leaves the step untouched on an unchanged outcome`() = runTest {
            val step = makeStep(content = "old content")
            step.lessonFingerprint = "fp-old"
            val verification = makeVerification(VerificationType.KNOWLEDGE, step.id, rubric = "r")
            val competency = Competency(key = "kotlin", label = "Kotlin", kind = CompetencyKind.SKILL)
            every { onboardingStepRepository.findById(step.id) } returns Optional.of(step)
            every { verificationRepository.findByStepId(step.id) } returns verification
            every { competencyRepository.findByKey("kotlin") } returns competency
            coEvery {
                onboardingAiClient.synthesizeLesson("kotlin", "Kotlin", "", "beginner", "fp-old")
            } returns LessonOutcome(status = "unchanged")

            service.synthesizeContent(step.id)

            assertEquals("old content", step.content)
            assertEquals("fp-old", step.lessonFingerprint)
        }

        @Test
        fun `omits the stored fingerprint when forceRegenerate is true`() = runTest {
            val step = makeStep()
            step.lessonFingerprint = "fp-old"
            val verification = makeVerification(VerificationType.KNOWLEDGE, step.id, rubric = "r")
            val competency = Competency(key = "kotlin", label = "Kotlin", kind = CompetencyKind.SKILL)
            every { onboardingStepRepository.findById(step.id) } returns Optional.of(step)
            every { verificationRepository.findByStepId(step.id) } returns verification
            every { competencyRepository.findByKey("kotlin") } returns competency
            coEvery {
                onboardingAiClient.synthesizeLesson("kotlin", "Kotlin", "", "beginner", null)
            } returns LessonOutcome(
                status = "synthesized",
                lesson = LessonContentSchema(
                    competencyKey = "kotlin",
                    level = "beginner",
                    title = "t",
                    body = "fresh body",
                ),
                provenance = LessonProvenanceSchema(corpusFingerprint = "fp-new"),
            )

            service.synthesizeContent(step.id, forceRegenerate = true)

            assertEquals("fresh body", step.content)
            assertEquals("fp-new", step.lessonFingerprint)
        }
    }
}
