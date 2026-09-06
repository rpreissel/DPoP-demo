package com.example.dpop.orchestrator.session

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test of [AuthEvidenceService]'s cache-invalidation side effect: a step-up must never
 * leave a stale, pre-step-up AccessToken sitting in an [AuthContext] the caller could still poll
 * for up to the token's own TTL - both [applyEvidence] (single tool outcome) and
 * [applyEvidenceUpdate] (native-Keycloak-style full-set sync) mutate the same [AuthEvidence] row a
 * token was minted from, so both must clear any [AuthContext] pointing at it.
 */
class AuthEvidenceServiceTest : BehaviorSpec({

    fun service(
        authEvidenceRepository: AuthEvidenceRepository,
        authContextRepository: AuthContextRepository
    ) = AuthEvidenceService(authEvidenceRepository, authContextRepository, mockk(relaxed = true))

    given("a step-up that adds a single tool's evidence (applyEvidence)") {
        then("clears the cached AccessToken of every AuthContext minted from that evidence") {
            val authEvidenceId = UUID.randomUUID()
            val evidence = AuthEvidence(accountId = 1L)
            val authEvidenceRepository = mockk<AuthEvidenceRepository>()
            every { authEvidenceRepository.findById(authEvidenceId) } returns Optional.of(evidence)
            every { authEvidenceRepository.save(any()) } answers { firstArg() }

            val staleContext = AuthContext(accountId = 1L).apply {
                tokenHandle = "stale-token"
                tokenExpiresAt = Instant.now().plusSeconds(300)
                refreshTokenHandle = "stale-refresh"
                refreshExpiresAt = Instant.now().plusSeconds(1800)
            }
            val authContextRepository = mockk<AuthContextRepository>()
            every { authContextRepository.findByAuthEvidenceId(authEvidenceId) } returns listOf(staleContext)
            every { authContextRepository.save(any()) } answers { firstArg() }

            service(authEvidenceRepository, authContextRepository).applyEvidence(authEvidenceId, emptyList())

            staleContext.tokenHandle shouldBe null
            staleContext.tokenExpiresAt shouldBe null
            staleContext.refreshTokenHandle shouldBe null
            staleContext.refreshExpiresAt shouldBe null
            verify { authContextRepository.save(staleContext) }
        }
    }

    given("no AuthContext was ever minted from this evidence") {
        then("is a no-op - nothing to invalidate, no pointless save") {
            val authEvidenceId = UUID.randomUUID()
            val evidence = AuthEvidence(accountId = 1L)
            val authEvidenceRepository = mockk<AuthEvidenceRepository>()
            every { authEvidenceRepository.findById(authEvidenceId) } returns Optional.of(evidence)
            every { authEvidenceRepository.save(any()) } answers { firstArg() }

            val authContextRepository = mockk<AuthContextRepository>()
            every { authContextRepository.findByAuthEvidenceId(authEvidenceId) } returns emptyList()

            service(authEvidenceRepository, authContextRepository).applyEvidenceUpdate(authEvidenceId, emptyList(), "kc")

            verify(exactly = 0) { authContextRepository.save(any()) }
        }
    }
})
