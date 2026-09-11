package com.example.dpop.orchestrator.session

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.policy.AuthPolicy
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.orchestrator.policy.MethodEvidence
import com.example.dpop.orchestrator.policy.MethodName
import com.nimbusds.jwt.PlainJWT
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test of [TokenService]'s mock token issuance - the three real branches of
 * `tokenFor` (still valid / silent refresh / full re-issuance) and the AccessToken's actual JWT
 * shape. Repository/AccountService/AuthEvidenceService/AuthPolicy are mocked; nothing here needs
 * a database or Spring context. `acr` is stubbed via [AuthPolicy.resolveAcr] directly (a fixed
 * "loa2") rather than exercised through real combination logic - that belongs to
 * `DefaultAuthPolicyTest`, not here.
 */
class TokenServiceTest : BehaviorSpec({

    fun authContext(
        accountId: Long? = 42L,
        tokenHandle: String? = null,
        tokenExpiresAt: Instant? = null,
        refreshTokenHandle: String? = null,
        refreshExpiresAt: Instant? = null,
        authEvidenceId: UUID? = UUID.randomUUID()
    ) = AuthContext(accountId = accountId).apply {
        this.tokenHandle = tokenHandle
        this.tokenExpiresAt = tokenExpiresAt
        this.refreshTokenHandle = refreshTokenHandle
        this.refreshExpiresAt = refreshExpiresAt
        this.authEvidenceId = authEvidenceId
    }

    fun evidence(accountId: Long? = 42L) = AuthEvidence(accountId = accountId).apply {
        addAmr(
            listOf(
                MethodEvidence(MethodName("sms"), AcrLevel("loa1"), amrSourceId = "auth-sms", source = AmrSource.ORCHESTRATOR),
                MethodEvidence(MethodName("password"), AcrLevel("loa1"), amrSourceId = "auth-password", source = AmrSource.ORCHESTRATOR),
            )
        )
    }

    fun evidenceService(authEvidenceId: UUID?, forAccount: AuthEvidence?): AuthEvidenceService {
        val service = mockk<AuthEvidenceService>()
        every { service.getAuthEvidence(any()) } returns null
        if (authEvidenceId != null) every { service.getAuthEvidence(authEvidenceId) } returns forAccount
        return service
    }

    fun policy(acr: String = "loa2"): AuthPolicy {
        val policy = mockk<AuthPolicy>()
        every { policy.resolveAcr(any(), any()) } returns acr
        return policy
    }

    fun service(
        repository: AuthContextRepository,
        authEvidenceService: AuthEvidenceService = mockk(relaxed = true),
        authPolicy: AuthPolicy = policy(),
        accountService: AccountService = mockk(relaxed = true),
        personDirectory: PersonDirectory = mockk(relaxed = true)
    ) = TokenService(repository, authEvidenceService, authPolicy, accountService, personDirectory)

    given("an AccessToken that still has well over minValiditySeconds left") {
        val authContextId = UUID.randomUUID()
        val ctx = authContext(tokenHandle = "existing-token", tokenExpiresAt = Instant.now().plusSeconds(300), refreshExpiresAt = Instant.now().plusSeconds(1800))
        val repository = mockk<AuthContextRepository>()
        every { repository.findById(authContextId) } returns Optional.of(ctx)

        then("it is returned unchanged - repeatable reads stay idempotent, nothing is minted or saved") {
            val result = service(repository).tokenFor(authContextId, minValiditySeconds = 15)

            result.accessToken shouldBe "existing-token"
            result.accessExpiresAt shouldBe ctx.tokenExpiresAt
            verify(exactly = 0) { repository.save(any()) }
        }
    }

    given("an AccessToken about to expire within minValiditySeconds, but the RefreshToken is still valid") {
        val authContextId = UUID.randomUUID()
        val originalRefreshHandle = "mockrt_original"
        val originalRefreshExpiry = Instant.now().plusSeconds(1000)
        val ctx = authContext(
            tokenHandle = "stale-token", tokenExpiresAt = Instant.now().plusSeconds(5),
            refreshTokenHandle = originalRefreshHandle, refreshExpiresAt = originalRefreshExpiry
        )
        val repository = mockk<AuthContextRepository>()
        every { repository.findById(authContextId) } returns Optional.of(ctx)
        every { repository.save(any()) } answers { firstArg() }

        then("a new AccessToken is minted, but the RefreshToken (handle and expiry) is left untouched") {
            val result = service(repository, evidenceService(ctx.authEvidenceId, evidence())).tokenFor(authContextId, minValiditySeconds = 15)

            result.accessToken shouldNotBe "stale-token"
            result.refreshExpiresAt shouldBe originalRefreshExpiry
            ctx.refreshTokenHandle shouldBe originalRefreshHandle
            verify(exactly = 1) { repository.save(ctx) }
        }
    }

    given("both the AccessToken and RefreshToken have expired") {
        val authContextId = UUID.randomUUID()
        val oldRefreshHandle = "mockrt_expired"
        val ctx = authContext(
            tokenHandle = "stale-token", tokenExpiresAt = Instant.now().minusSeconds(5),
            refreshTokenHandle = oldRefreshHandle, refreshExpiresAt = Instant.now().minusSeconds(5)
        )
        val repository = mockk<AuthContextRepository>()
        every { repository.findById(authContextId) } returns Optional.of(ctx)
        every { repository.save(any()) } answers { firstArg() }

        then("a full re-issuance mints both a new AccessToken and a new RefreshToken") {
            val result = service(repository, evidenceService(ctx.authEvidenceId, evidence())).tokenFor(authContextId)

            result.accessToken shouldNotBe "stale-token"
            ctx.refreshTokenHandle shouldNotBe oldRefreshHandle
            result.refreshExpiresAt.isAfter(Instant.now()) shouldBe true
        }
    }

    given("no AuthContext under the given id") {
        then("it fails loudly rather than minting a token for nothing") {
            val authContextId = UUID.randomUUID()
            val repository = mockk<AuthContextRepository>()
            every { repository.findById(authContextId) } returns Optional.empty()

            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                service(repository).tokenFor(authContextId)
            }
        }
    }

    given("minting a fresh AccessToken") {
        val authContextId = UUID.randomUUID()
        val ctx = authContext(accountId = 99L)
        val repository = mockk<AuthContextRepository>()
        every { repository.findById(authContextId) } returns Optional.of(ctx)
        every { repository.save(any()) } answers { firstArg() }

        then("it is a spec-shaped unsecured JWT carrying the session's own acr/amr, parseable without a key") {
            val result = service(repository, evidenceService(ctx.authEvidenceId, evidence(accountId = 99L))).tokenFor(authContextId)

            val claims = PlainJWT.parse(result.accessToken).jwtClaimsSet
            claims.subject shouldBe "99"
            claims.getStringClaim("acr") shouldBe "loa2"
            claims.getStringListClaim("amr") shouldBe listOf("sms", "password")
            claims.issuer shouldBe "mock-keycloak"
            claims.audience shouldBe listOf("dpop-demo-orchestrator")
            // JWT's `exp` is a NumericDate (whole seconds, RFC 7519 #2) - compare at that
            // granularity rather than exact Instant equality, which would fail on sub-second
            // truncation alone, not a real bug.
            claims.expirationTime.toInstant().epochSecond shouldBe result.accessExpiresAt.epochSecond
        }
    }

    given("resolving the ID-token claims for a known account") {
        then("the fachliche claim set carries account and person identifiers, not just the raw session state") {
            val authContextId = UUID.randomUUID()
            val ctx = authContext(accountId = 7L)
            ctx.authTime = Instant.now()
            val repository = mockk<AuthContextRepository>()
            every { repository.findById(authContextId) } returns Optional.of(ctx)
            val accountService = mockk<AccountService>()
            every { accountService.findAccount(7L) } returns com.example.dpop.account.AccountProfile(
                accountId = 7L, personId = 55L, identifications = emptyList(), authenticationMethods = emptyList(),
                email = "max@example.test", emailConfirmedAt = Instant.now()
            )

            val claims = service(repository, evidenceService(ctx.authEvidenceId, evidence(accountId = 7L)), accountService = accountService).idClaims(authContextId)

            claims["sub"] shouldBe "7"
            claims["personId"] shouldBe 55L
            claims["email"] shouldBe "max@example.test"
            claims["email_verified"] shouldBe true
        }
    }
})
