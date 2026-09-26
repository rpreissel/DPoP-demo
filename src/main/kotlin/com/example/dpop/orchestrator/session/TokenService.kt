package com.example.dpop.orchestrator.session

import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.policy.AuthEvidence as CoreAuthEvidence
import com.example.dpop.orchestrator.policy.AuthPolicy
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_spi.AttributeType
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

data class TokenPair(
    val accessToken: String,
    val accessExpiresAt: Instant,
    val refreshExpiresAt: Instant
)

/**
 * Mock Keycloak token issuance (docs/08-projektrahmen.md: the real Keycloak facade is out of
 * scope). The AccessToken is a spec-shaped unsecured JWT (RFC 7519 #6, alg=none) so the frontend
 * can parse and display its claims without a JWT library or a signing key. The RefreshToken is an
 * opaque server-side secret - it is never returned to a caller, only its expiry is. Claims are
 * resolved through [AuthContext.authEvidenceId], not stored on [AuthContext] itself - see that
 * entity's own doc for why.
 */
@Service
// SessionExpiredException is an answer, not a failure: the caller ends the channel in the same
// transaction, which must still commit (ChannelService.getToken).
@Transactional(noRollbackFor = [SessionExpiredException::class])
class TokenService(
    private val authContextRepository: AuthContextRepository,
    private val authEvidenceService: AuthEvidenceService,
    private val authPolicy: AuthPolicy,
    private val accountService: AccountService,
    private val personDirectory: PersonDirectory
) {

    /**
     * Covers first issuance and refresh alike - the caller never chooses which happens, only how
     * fresh the result must be. [minValiditySeconds] is the caller's tolerance: if the current
     * AccessToken still has at least that much life left, it comes back unchanged (repeatable
     * reads stay idempotent); otherwise a new one is minted with the remembered RefreshToken
     * (silent refresh), which also moves the refresh window on - an idle timeout of [REFRESH_TTL].
     *
     * A RefreshToken that exists but has expired ends the login: [SessionExpiredException], never a
     * fresh issuance (review 2026-09, M-4). Only the very first call, before any RefreshToken
     * exists, issues one.
     */
    fun tokenFor(authContextId: UUID, minValiditySeconds: Long = DEFAULT_MIN_VALIDITY_SECONDS): TokenPair {
        val authContext = checkNotNull(authContextRepository.findByIdOrNull(authContextId)) {
            "AuthContext not found: $authContextId"
        }
        val now = Instant.now()

        val currentExpiry = authContext.accessExpiresAt
        if (authContext.accessToken != null && currentExpiry != null &&
            currentExpiry.isAfter(now.plusSeconds(minValiditySeconds))
        ) {
            return TokenPair(authContext.accessToken!!, currentExpiry, authContext.refreshExpiresAt!!)
        }

        if (authContext.refreshToken == null) {
            authContext.refreshToken = "mockrt_${UUID.randomUUID()}"
        } else if (authContext.refreshExpiresAt?.isAfter(now) != true) {
            throw SessionExpiredException("Refresh window of AuthContext $authContextId has lapsed")
        }
        // Sliding: every refresh moves the window on, so it lapses only after REFRESH_TTL of idleness.
        authContext.refreshExpiresAt = now.plus(REFRESH_TTL)

        val accessExpiresAt = now.plus(ACCESS_TTL)
        authContext.accessToken = mintAccessToken(authContext, now, accessExpiresAt)
        authContext.accessExpiresAt = accessExpiresAt
        authContextRepository.save(authContext)

        return TokenPair(authContext.accessToken!!, accessExpiresAt, authContext.refreshExpiresAt!!)
    }

    /** The fachliche (business) ID-token claims - a separate JSON shape from the AccessToken, on purpose. */
    fun idClaims(authContextId: UUID): Map<String, Any?> {
        val authContext = checkNotNull(authContextRepository.findByIdOrNull(authContextId)) {
            "AuthContext not found: $authContextId"
        }
        val account = authContext.accountId?.let { accountService.findAccount(it) }
        val evidence = evidenceFor(authContext)
        return mapOf(
            "sub" to authContext.accountId?.toString(),
            "acr" to evidence?.let { authPolicy.resolveAcr(it, account) }?.value,
            "amr" to (evidence?.amr?.map { m -> m.value } ?: emptyList()),
            "auth_time" to authContext.authTime?.epochSecond,
            "accountId" to authContext.accountId,
            "personId" to account?.personId,
            // Together with personId the account's role (ADR-34): Versicherter with, Partner
            // without a Versicherungsnummer, Interessent without a person at all. Read live - the
            // Personenverzeichnis is its authority.
            "versnr" to account?.personId?.let(personDirectory::insuranceNumberOf),
            "name" to displayName(account),
            "email" to account?.email,
            "email_verified" to (account?.emailConfirmed ?: false)
        )
    }

    /**
     * Who is logged in: the register person's display name when one is bound; otherwise the
     * account's own attested name/vorname (an Interessent carries both as claims since ADR-18,
     * even without a register binding); `null` only for an account that has neither (enrollment
     * first, nothing attested yet).
     */
    private fun displayName(account: AccountProfile?): String? {
        account ?: return null
        account.personId?.let { return personDirectory.displayName(it) }
        val attested = accountService.establishedClaimValues(account.accountId, setOf(AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES))
        return listOfNotNull(attested[AttributeType.GIVEN_NAMES], attested[AttributeType.FAMILY_NAME])
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ")
    }

    /** The core, policy-evaluable evidence this token context's paired [AuthEvidence] currently holds - `null` if none was ever recorded. */
    private fun evidenceFor(authContext: AuthContext): CoreAuthEvidence? =
        authContext.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) }?.toCoreEvidence()

    private fun mintAccessToken(authContext: AuthContext, iat: Instant, exp: Instant): String {
        val account = authContext.accountId?.let { accountService.findAccount(it) }
        val evidence = evidenceFor(authContext)
        val claims = JWTClaimsSet.Builder()
            .subject(authContext.accountId?.toString())
            .issuer(MOCK_ISSUER)
            .audience(MOCK_AUDIENCE)
            .claim("acr", evidence?.let { authPolicy.resolveAcr(it, account) }?.value)
            .claim("amr", evidence?.amr?.map { it.value } ?: emptyList<String>())
            .issueTime(Date.from(iat))
            .expirationTime(Date.from(exp))
            .jwtID(UUID.randomUUID().toString())
            .build()
        return PlainJWT(claims).serialize()
    }

    companion object {
        const val DEFAULT_MIN_VALIDITY_SECONDS: Long = 15
        private val ACCESS_TTL: Duration = Duration.ofMinutes(5)
        private val REFRESH_TTL: Duration = Duration.ofMinutes(30)
        private const val MOCK_ISSUER = "mock-keycloak"
        private const val MOCK_AUDIENCE = "dpop-demo-orchestrator"
    }
}
