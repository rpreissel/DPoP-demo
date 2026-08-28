package com.example.dpop.orchestrator.session

import com.example.dpop.account.AccountService
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
 * Mock Keycloak token issuance (docs/11-umsetzungsplan.md: the real Keycloak facade is out of
 * scope). The AccessToken is a spec-shaped unsecured JWT (RFC 7519 #6, alg=none) so the frontend
 * can parse and display its claims without a JWT library or a signing key. The RefreshToken is an
 * opaque server-side secret - it is never returned to a caller, only its expiry is.
 */
@Service
@Transactional
class TokenService(
    private val authContextRepository: AuthContextRepository,
    private val accountService: AccountService
) {

    /**
     * Covers first issuance and refresh alike - the caller never chooses which happens, only how
     * fresh the result must be. [minValiditySeconds] is the caller's tolerance: if the current
     * AccessToken still has at least that much life left, it comes back unchanged (repeatable
     * reads stay idempotent); otherwise a new one is minted, using the remembered RefreshToken
     * where possible (silent refresh) or a full re-issuance if that too has expired.
     */
    fun tokenFor(authContextId: UUID, minValiditySeconds: Long = DEFAULT_MIN_VALIDITY_SECONDS): TokenPair {
        val authContext = requireNotNull(authContextRepository.findByIdOrNull(authContextId)) {
            "AuthContext not found: $authContextId"
        }
        val now = Instant.now()

        val currentExpiry = authContext.tokenExpiresAt
        if (authContext.tokenHandle != null && currentExpiry != null &&
            currentExpiry.isAfter(now.plusSeconds(minValiditySeconds))
        ) {
            return TokenPair(authContext.tokenHandle!!, currentExpiry, authContext.refreshExpiresAt!!)
        }

        val refreshStillValid = authContext.refreshTokenHandle != null &&
            authContext.refreshExpiresAt?.isAfter(now) == true
        if (!refreshStillValid) {
            authContext.refreshTokenHandle = "mockrt_${UUID.randomUUID()}"
            authContext.refreshExpiresAt = now.plus(REFRESH_TTL)
        }

        val accessExpiresAt = now.plus(ACCESS_TTL)
        authContext.tokenHandle = mintAccessToken(authContext, now, accessExpiresAt)
        authContext.tokenExpiresAt = accessExpiresAt
        authContextRepository.save(authContext)

        return TokenPair(authContext.tokenHandle!!, accessExpiresAt, authContext.refreshExpiresAt!!)
    }

    /** The fachliche (business) ID-token claims - a separate JSON shape from the AccessToken, on purpose. */
    fun idClaims(authContextId: UUID): Map<String, Any?> {
        val authContext = requireNotNull(authContextRepository.findByIdOrNull(authContextId)) {
            "AuthContext not found: $authContextId"
        }
        val account = authContext.accountId?.let { accountService.findAccount(it) }
        return mapOf(
            "sub" to authContext.accountId?.toString(),
            "acr" to authContext.currentAcr,
            "amr" to authContext.currentAmr,
            "auth_time" to authContext.authTime?.epochSecond,
            "accountId" to authContext.accountId,
            "personId" to account?.personId,
            "email" to account?.email,
            "email_verified" to (account?.emailConfirmed ?: false)
        )
    }

    private fun mintAccessToken(authContext: AuthContext, iat: Instant, exp: Instant): String {
        val claims = JWTClaimsSet.Builder()
            .subject(authContext.accountId?.toString())
            .issuer(MOCK_ISSUER)
            .audience(MOCK_AUDIENCE)
            .claim("acr", authContext.currentAcr)
            .claim("amr", authContext.currentAmr)
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
