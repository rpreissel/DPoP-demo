package com.example.dpop.orchestrator.session

import org.springframework.web.client.HttpClientErrorException
import org.springframework.http.HttpStatus
import org.springframework.http.HttpHeaders
import io.kotest.assertions.throwables.shouldThrow
import com.example.dpop.orchestrator.domain.ChannelType
import com.example.dpop.account.AccountService
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.orchestrator.kc.AccountTokenResponse
import com.example.dpop.orchestrator.kc.KeycloakAdminClient
import com.example.dpop.orchestrator.domain.policy.AuthPolicy
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test of [KcTokenProvider] - the `keycloak`-profile [TokenProvider] (DPoP-demo-xso).
 * Covers the three branches that matter for a real-token path: the still-valid short-circuit
 * stays identical to [TokenService]'s, an expiring-but-unchanged-evidence token is renewed via
 * Keycloak's own cheap `refresh_token` grant, and a genuinely fresh mint sends the current acr/amr
 * to the account-token grant (ADR-9, addendum F-6: no per-account assertion any more).
 */
class KcTokenProviderTest : BehaviorSpec({

    fun provider(
        authContextRepository: AuthContextRepository,
        keycloakAdminClient: KeycloakAdminClient = mockk(),
        authEvidenceService: AuthEvidenceService = mockk { every { getAuthEvidence(any()) } returns null },
        authPolicy: AuthPolicy = mockk(relaxed = true),
        accountService: AccountService = mockk(relaxed = true)
    ) = KcTokenProvider(authContextRepository, keycloakAdminClient, authEvidenceService, authPolicy, accountService)

    fun appChannel(authContextId: UUID) = ChannelSession(channel = ChannelType.APP).apply {
        this.authContextId = authContextId
    }

    given("an AccessToken that still has well over minValiditySeconds left") {
        then("it is returned unchanged - no grant call") {
            val authContextId = UUID.randomUUID()
            val expiry = Instant.now().plusSeconds(300)
            val ctx = AuthContext(accountId = 42L).apply { accessToken = "existing-token"; accessExpiresAt = expiry }
            val authContextRepository = mockk<AuthContextRepository>()
            every { authContextRepository.findById(authContextId) } returns Optional.of(ctx)
            val keycloakAdminClient = mockk<KeycloakAdminClient>()

            val result = provider(authContextRepository, keycloakAdminClient = keycloakAdminClient)
                .tokenFor(appChannel(authContextId), minValiditySeconds = 15)

            result.accessToken shouldBe "existing-token"
            verify(exactly = 0) { keycloakAdminClient.requestAccountToken(any(), any(), any()) }
            verify(exactly = 0) { keycloakAdminClient.refreshAccountToken(any()) }
        }
    }

    given("an expiring AccessToken whose RefreshToken is still valid (no step-up in between)") {
        then("renews via Keycloak's own refresh_token grant") {
            val authContextId = UUID.randomUUID()
            val accountId = 3L
            val ctx = AuthContext(accountId = accountId).apply {
                accessToken = "stale"; accessExpiresAt = Instant.now().minusSeconds(5)
                refreshToken = "existing-refresh"; refreshExpiresAt = Instant.now().plusSeconds(600)
            }
            val authContextRepository = mockk<AuthContextRepository>()
            every { authContextRepository.findById(authContextId) } returns Optional.of(ctx)
            every { authContextRepository.save(any()) } answers { firstArg() }
            val keycloakAdminClient = mockk<KeycloakAdminClient>()
            every { keycloakAdminClient.refreshAccountToken("existing-refresh") } returns
                AccountTokenResponse("renewed-access-token", 300, "rotated-refresh", 600)

            val result = provider(authContextRepository, keycloakAdminClient).tokenFor(appChannel(authContextId))

            result.accessToken shouldBe "renewed-access-token"
            ctx.refreshToken shouldBe "rotated-refresh"
            verify(exactly = 0) { keycloakAdminClient.requestAccountToken(any(), any(), any()) }
        }
    }

    given("a RefreshToken whose window has lapsed, or that Keycloak refuses (review 2026-09, M-4)") {
        fun contextWith(refreshExpiresAt: Instant) = AuthContext(accountId = 3L).apply {
            accessToken = "stale"; accessExpiresAt = Instant.now().minusSeconds(5)
            refreshToken = "existing-refresh"; this.refreshExpiresAt = refreshExpiresAt
        }
        fun repositoryWith(id: UUID, ctx: AuthContext) = mockk<AuthContextRepository>().also {
            every { it.findById(id) } returns Optional.of(ctx)
            every { it.save(any()) } answers { firstArg() }
        }

        then("a lapsed window ends the login - no new Keycloak session is opened behind Keycloak's back") {
            val id = UUID.randomUUID()
            val keycloakAdminClient = mockk<KeycloakAdminClient>()
            shouldThrow<SessionExpiredException> {
                provider(repositoryWith(id, contextWith(Instant.now().minusSeconds(1))), keycloakAdminClient).tokenFor(appChannel(id))
            }
            verify(exactly = 0) { keycloakAdminClient.requestAccountToken(any(), any(), any()) }
        }

        then("a refresh Keycloak refuses (its session ended) ends the login too") {
            val id = UUID.randomUUID()
            val keycloakAdminClient = mockk<KeycloakAdminClient>()
            every { keycloakAdminClient.refreshAccountToken("existing-refresh") } throws
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "invalid_grant", HttpHeaders.EMPTY, ByteArray(0), null)
            shouldThrow<SessionExpiredException> {
                provider(repositoryWith(id, contextWith(Instant.now().plusSeconds(600))), keycloakAdminClient).tokenFor(appChannel(id))
            }
            verify(exactly = 0) { keycloakAdminClient.requestAccountToken(any(), any(), any()) }
        }
    }

    given("an expired AccessToken with no valid RefreshToken (first issuance, or a step-up just invalidated the cache)") {
        then("asks the account-token grant for a fresh token carrying the current acr/amr") {
            val authContextId = UUID.randomUUID()
            val accountId = 7L
            val authEvidenceId = UUID.randomUUID()
            val ctx = AuthContext(accountId = accountId).apply {
                accessToken = "stale"; accessExpiresAt = Instant.now().minusSeconds(5)
                this.authEvidenceId = authEvidenceId
            }
            val authContextRepository = mockk<AuthContextRepository>()
            every { authContextRepository.findById(authContextId) } returns Optional.of(ctx)
            every { authContextRepository.save(any()) } answers { firstArg() }
            val keycloakAdminClient = mockk<KeycloakAdminClient>()
            every { keycloakAdminClient.requestAccountToken(accountId, "loa2", any()) } returns
                AccountTokenResponse("real-access-token", 300, "fresh-refresh", 600)
            val authPolicy = mockk<AuthPolicy> { every { resolveAcr(any(), any()) } returns AcrLevel.LOA2 }
            val authEvidenceService = mockk<AuthEvidenceService> {
                every { getAuthEvidence(authEvidenceId) } returns EvidenceTrail(accountId = accountId)
            }
            val accountService = mockk<AccountService> {
                every { findAccount(accountId) } returns com.example.dpop.account.AccountProfile(
                    accountId = accountId, personId = "P000000001",
                    authenticationMethods = listOf(
                        com.example.dpop.account.AuthMethodView(
                            id = "m1", method = "password", active = true,
                            createdAt = Instant.now(), enrolledUnderAcr = "loa1", details = null,
                            enrollmentRef = com.example.dpop.tool_spi.EnrollmentRef("auth_password.enrollment", "1")
                        )
                    )
                )
            }

            val result = provider(authContextRepository, keycloakAdminClient, authEvidenceService, authPolicy, accountService)
                .tokenFor(appChannel(authContextId))

            result.accessToken shouldBe "real-access-token"
            ctx.accessToken shouldBe "real-access-token"
            ctx.refreshToken shouldBe "fresh-refresh"
            verify { keycloakAdminClient.requestAccountToken(accountId, "loa2", any()) }
        }
    }
})
