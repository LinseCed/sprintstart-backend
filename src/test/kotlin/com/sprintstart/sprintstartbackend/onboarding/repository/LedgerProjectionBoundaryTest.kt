package com.sprintstart.sprintstartbackend.onboarding.repository

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencySource
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingPath
import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserCompetencyState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.crypto.encrypt.BytesEncryptor
import org.springframework.security.crypto.encrypt.Encryptors
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Guards the ledger/projection boundary at the persistence layer.
 *
 * The onboarding path is a disposable projection; the `UserCompetencyState` ledger is durable.
 * Regenerating a path deletes the projection rows, and this test proves that deletion never
 * cascades into the ledger — the risk being an accidental JPA relationship or shared lifecycle.
 * A local [BytesEncryptor] is supplied because the onboarding entity model registers an encrypted
 * attribute converter that the JPA slice would otherwise fail to construct.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(LedgerProjectionBoundaryTest.CryptoTestConfig::class)
@ActiveProfiles("test")
class LedgerProjectionBoundaryTest
    @Autowired
    constructor(
        private val onboardingPathRepository: OnboardingPathRepository,
        private val userCompetencyStateRepository: UserCompetencyStateRepository,
    ) {
        @Test
        fun `deleting a user's onboarding path leaves their competency ledger untouched`() {
            val userId = UUID.randomUUID()
            userCompetencyStateRepository.saveAndFlush(
                UserCompetencyState(
                    userId = userId,
                    competencyKey = "kotlin",
                    level = 3,
                    source = CompetencySource.VERIFIED,
                ),
            )
            onboardingPathRepository.saveAndFlush(OnboardingPath(userId = userId))

            onboardingPathRepository.deleteByUserId(userId)
            onboardingPathRepository.flush()

            assertThat(onboardingPathRepository.existsByUserId(userId)).isFalse()
            val ledger = userCompetencyStateRepository.findAllByUserId(userId)
            assertThat(ledger).hasSize(1)
            assertThat(ledger[0].level).isEqualTo(3)
            assertThat(ledger[0].source).isEqualTo(CompetencySource.VERIFIED)
        }

        @TestConfiguration
        class CryptoTestConfig {
            @Bean
            fun bytesEncryptor(): BytesEncryptor = Encryptors.stronger("deadbeef", "deadbeef")
        }
    }
