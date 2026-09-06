package com.example.dpop.orchestrator.session

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.kc.AccountKeycloakKeypair
import com.example.dpop.orchestrator.kc.AccountKeypairService
import com.example.dpop.orchestrator.kc.AccountTokenResponse
import com.example.dpop.orchestrator.kc.KeycloakAdminClient
import com.example.dpop.orchestrator.policy.AuthPolicy
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.SignedJWT
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test of [KcTokenProvider] - the `keycloak`-profile [TokenProvider] (DPoP-demo-xso).
 * Covers the three branches that matter for a real-token path: the still-valid short-circuit
 * stays identical to [TokenService]'s, an expiring-but-unchanged-evidence token is renewed via
 * Keycloak's own cheap `refresh_token` grant (no assertion/private key involved), and a genuinely
 * fresh mint self-heals the account's keypair (generate-if-absent, re-push to Keycloak
 * unconditionally) rather than assuming a prior sync already ran - see [signAssertion]'s own doc
 * for the live race this fixes.
 */
class KcTokenProviderTest : BehaviorSpec({

    fun keypair(accountId: Long): AccountKeycloakKeypair {
        val key = ECKeyGenerator(Curve.P_256).keyID("account-$accountId").generate()
        return AccountKeycloakKeypair(
            accountId = accountId,
            publicKeyJwk = key.toPublicJWK().toJSONString(),
            privateKeyJwk = key.toJSONString()
        )
    }

    fun provider(
        authContextRepository: AuthContextRepository,
        accountKeypairService: AccountKeypairService = mockk(),
        keycloakAdminClient: KeycloakAdminClient = mockk(),
        authEvidenceService: AuthEvidenceService = mockk { every { getAuthEvidence(any()) } returns null },
        authPolicy: AuthPolicy = mockk(relaxed = true),
        accountService: AccountService = mockk(relaxed = true)
    ) = KcTokenProvider(authContextRepository, accountKeypairService, keycloakAdminClient, authEvidenceService, authPolicy, accountService)

    fun appChannel(authContextId: UUID) = ChannelSession(channel = ChannelSession.Channel.APP).apply {
        this.authContextId = authContextId
    }

    given("an AccessToken that still has well over minValiditySeconds left") {
        then("it is returned unchanged - no grant call, no keypair lookup") {
            val authContextId = UUID.randomUUID()
            val expiry = Instant.now().plusSeconds(300)
            val ctx = AuthContext(accountId = 42L).apply { tokenHandle = "existing-token"; tokenExpiresAt = expiry }
            val authContextRepository = mockk<AuthContextRepository>()
            every { authContextRepository.findById(authContextId) } returns Optional.of(ctx)
            val keycloakAdminClient = mockk<KeycloakAdminClient>()

            val result = provider(authContextRepository, keycloakAdminClient = keycloakAdminClient)
                .tokenFor(appChannel(authContextId), minValiditySeconds = 15)

            result.accessToken shouldBe "existing-token"
            verify(exactly = 0) { keycloakAdminClient.requestAccountToken(any(), any()) }
            verify(exactly = 0) { keycloakAdminClient.refreshAccountToken(any()) }
        }
    }

    given("an expiring AccessToken whose RefreshToken is still valid (no step-up in between)") {
        then("renews via Keycloak's own refresh_token grant - no assertion, no keypair involved") {
            val authContextId = UUID.randomUUID()
            val accountId = 3L
            val ctx = AuthContext(accountId = accountId).apply {
                tokenHandle = "stale"; tokenExpiresAt = Instant.now().minusSeconds(5)
                refreshTokenHandle = "existing-refresh"; refreshExpiresAt = Instant.now().plusSeconds(600)
            }
            val authContextRepository = mockk<AuthContextRepository>()
            every { authContextRepository.findById(authContextId) } returns Optional.of(ctx)
            every { authContextRepository.save(any()) } answers { firstArg() }
            val accountKeypairService = mockk<AccountKeypairService>()
            val keycloakAdminClient = mockk<KeycloakAdminClient>()
            every { keycloakAdminClient.refreshAccountToken("existing-refresh") } returns
                AccountTokenResponse("renewed-access-token", 300, "rotated-refresh", 600)

            val result = provider(authContextRepository, accountKeypairService, keycloakAdminClient).tokenFor(appChannel(authContextId))

            result.accessToken shouldBe "renewed-access-token"
            ctx.refreshTokenHandle shouldBe "rotated-refresh"
            verify(exactly = 0) { keycloakAdminClient.requestAccountToken(any(), any()) }
            verify(exactly = 0) { accountKeypairService.keypairFor(any()) }
        }
    }

    given("an expired AccessToken with no valid RefreshToken (first issuance, or a step-up just invalidated the cache)") {
        then("self-heals the keypair (generate-if-absent, re-push to Keycloak) and mints a fresh assertion carrying acr/amr") {
            val authContextId = UUID.randomUUID()
            val accountId = 7L
            val authEvidenceId = UUID.randomUUID()
            val ctx = AuthContext(accountId = accountId).apply {
                tokenHandle = "stale"; tokenExpiresAt = Instant.now().minusSeconds(5)
                this.authEvidenceId = authEvidenceId
            }
            val authContextRepository = mockk<AuthContextRepository>()
            every { authContextRepository.findById(authContextId) } returns Optional.of(ctx)
            every { authContextRepository.save(any()) } answers { firstArg() }
            val kp = keypair(accountId)
            val accountKeypairService = mockk<AccountKeypairService>()
            every { accountKeypairService.keypairFor(accountId) } returns kp
            val assertionSlot = slot<String>()
            val keycloakAdminClient = mockk<KeycloakAdminClient>()
            every { keycloakAdminClient.setPublicKeyCredential(accountId, kp.publicKeyJwk, any()) } returns Unit
            every { keycloakAdminClient.requestAccountToken(accountId, capture(assertionSlot)) } returns
                AccountTokenResponse("real-access-token", 300, "fresh-refresh", 600)
            val authPolicy = mockk<AuthPolicy> { every { resolveAcr(any(), any()) } returns "loa2" }
            val authEvidenceService = mockk<AuthEvidenceService> {
                every { getAuthEvidence(authEvidenceId) } returns AuthEvidence(accountId = accountId)
            }
            val accountService = mockk<AccountService> {
                every { findAccount(accountId) } returns com.example.dpop.account.AccountProfile(
                    accountId = accountId, personId = 1L, identifications = emptyList(),
                    authenticationMethods = listOf(
                        com.example.dpop.account.AuthMethodView(
                            id = "m1", method = "password", active = true,
                            createdAt = Instant.now(), enrolledUnderAcr = "loa1", details = null
                        )
                    )
                )
            }

            val result = provider(authContextRepository, accountKeypairService, keycloakAdminClient, authEvidenceService, authPolicy, accountService)
                .tokenFor(appChannel(authContextId))

            result.accessToken shouldBe "real-access-token"
            ctx.tokenHandle shouldBe "real-access-token"
            ctx.refreshTokenHandle shouldBe "fresh-refresh"
            verify { keycloakAdminClient.setPublicKeyCredential(accountId, kp.publicKeyJwk, listOf("password")) }

            val jwt = SignedJWT.parse(assertionSlot.captured)
            jwt.verify(ECDSAVerifier(com.nimbusds.jose.jwk.ECKey.parse(kp.publicKeyJwk))) shouldBe true
            jwt.jwtClaimsSet.subject shouldBe accountId.toString()
            jwt.jwtClaimsSet.audience shouldBe listOf(KeycloakAdminClient.ACCOUNT_TOKEN_GRANT_TYPE)
            jwt.jwtClaimsSet.getStringClaim("acr") shouldBe "loa2"
        }
    }
})
