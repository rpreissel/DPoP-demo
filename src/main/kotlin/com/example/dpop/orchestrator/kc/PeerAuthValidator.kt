package com.example.dpop.orchestrator.kc

import com.example.dpop.orchestrator.dpop.DpopReplayProtectionService
import com.example.dpop.orchestrator.dpop.DpopValidationException
import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.text.ParseException
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Verifies the signed JWT Keycloak sends on every kc-facade request (docs/ideen/
 * web-keycloak-kanal.md #3) - one assertion per request instead of an access token plus a
 * separate proof, because the initial login has no `sub` yet.
 *
 * Reuses [DpopReplayProtectionService] for single-use enforcement, keyed under a `kc:`-prefixed
 * namespace so peer-auth `jti`s never collide with DPoP-proof `jti`s in the same table.
 */
@Component
class PeerAuthValidator(
    private val jwkSource: KeycloakJwkSource,
    private val replayProtectionService: DpopReplayProtectionService,
    @Value("\${kc.peer-auth.issuer}") private val expectedIssuer: String,
    @Value("\${kc.peer-auth.audience}") private val expectedAudience: String,
    @Value("\${kc.peer-auth.max-clock-skew-seconds:30}") private val maxClockSkewSeconds: Long,
    @Value("\${kc.peer-auth.max-age-seconds:30}") private val maxAssertionAgeSeconds: Long
) {

    fun validate(assertion: String?, httpMethod: String, httpUrl: String): PeerAuthAssertion {
        if (assertion.isNullOrBlank()) {
            throw PeerAuthValidationException("Missing peer-auth assertion")
        }

        val signedJWT = try {
            SignedJWT.parse(assertion)
        } catch (e: ParseException) {
            throw PeerAuthValidationException("Invalid peer-auth assertion format", e)
        }

        val header = signedJWT.header
        if (header.algorithm !in SUPPORTED_ALGORITHMS) {
            throw PeerAuthValidationException("Unsupported peer-auth algorithm: ${header.algorithm}")
        }
        val kid = header.keyID ?: throw PeerAuthValidationException("Peer-auth assertion is missing kid")

        val claims: JWTClaimsSet = try {
            signedJWT.jwtClaimsSet
        } catch (e: ParseException) {
            throw PeerAuthValidationException("Invalid peer-auth claims", e)
        }

        val jwk = jwkSource.find(kid) ?: throw PeerAuthValidationException("Unknown peer-auth key id: $kid")
        validateSignature(signedJWT, jwk)
        validateClaims(claims, httpMethod, httpUrl)

        val channelAnchor = claims.getStringClaim("channel_anchor")
        if (channelAnchor.isNullOrBlank()) {
            throw PeerAuthValidationException("Peer-auth assertion is missing channel_anchor")
        }

        val jti = claims.jwtid
        if (jti.isNullOrBlank()) {
            throw PeerAuthValidationException("Peer-auth jti claim is missing")
        }
        val issuedAt = claims.issueTime?.toInstant()
            ?: throw PeerAuthValidationException("Peer-auth iat claim is missing")
        val replayKeyExpiresAt = issuedAt.plus(maxAssertionAgeSeconds + maxClockSkewSeconds, ChronoUnit.SECONDS)
        try {
            replayProtectionService.validateAndStore("kc:$kid", jti, replayKeyExpiresAt)
        } catch (e: DpopValidationException) {
            // Shared single-use store (docs/ideen/web-keycloak-kanal.md #3) - reported under this
            // validator's own exception type so callers only ever catch PeerAuthValidationException.
            throw PeerAuthValidationException("Peer-auth assertion replay detected", e)
        }

        return PeerAuthAssertion(jti, issuedAt, channelAnchor, claims.subject)
    }

    private fun validateSignature(signedJWT: SignedJWT, jwk: JWK) {
        try {
            val valid = when (jwk) {
                is RSAKey -> signedJWT.verify(RSASSAVerifier(jwk.toRSAPublicKey()))
                is ECKey -> signedJWT.verify(ECDSAVerifier(jwk.toECPublicKey()))
                else -> throw PeerAuthValidationException("Unsupported key type: ${jwk.keyType}")
            }
            if (!valid) {
                throw PeerAuthValidationException("Invalid peer-auth assertion signature")
            }
        } catch (e: JOSEException) {
            throw PeerAuthValidationException("Failed to verify peer-auth assertion signature", e)
        }
    }

    private fun validateClaims(claims: JWTClaimsSet, httpMethod: String, httpUrl: String) {
        if (claims.issuer != expectedIssuer) {
            throw PeerAuthValidationException("Unexpected peer-auth issuer: ${claims.issuer}")
        }
        if (expectedAudience !in claims.audience.orEmpty()) {
            throw PeerAuthValidationException("Unexpected peer-auth audience: ${claims.audience}")
        }

        val htm = claims.getStringClaim("htm")
        if (htm == null || !htm.equals(httpMethod, ignoreCase = true)) {
            throw PeerAuthValidationException("Peer-auth htm claim does not match request method")
        }
        val htu = claims.getStringClaim("htu")
        if (htu == null || !normalizeUrl(htu).equals(normalizeUrl(httpUrl), ignoreCase = true)) {
            throw PeerAuthValidationException("Peer-auth htu claim does not match request URL")
        }

        val issuedAt = claims.issueTime?.toInstant()
            ?: throw PeerAuthValidationException("Peer-auth iat claim is missing")
        val now = Instant.now()
        if (issuedAt.isAfter(now.plus(maxClockSkewSeconds, ChronoUnit.SECONDS))) {
            throw PeerAuthValidationException("Peer-auth iat claim is in the future")
        }
        if (issuedAt.isBefore(now.minus(maxAssertionAgeSeconds, ChronoUnit.SECONDS))) {
            throw PeerAuthValidationException("Peer-auth iat claim is too old")
        }
    }

    private fun normalizeUrl(url: String): String {
        val queryIndex = url.indexOf('?')
        val withoutQuery = if (queryIndex >= 0) url.substring(0, queryIndex) else url
        val fragmentIndex = withoutQuery.indexOf('#')
        return if (fragmentIndex >= 0) withoutQuery.substring(0, fragmentIndex) else withoutQuery
    }

    companion object {
        private val SUPPORTED_ALGORITHMS: Set<JWSAlgorithm> = setOf(
            JWSAlgorithm.RS256,
            JWSAlgorithm.ES256,
            JWSAlgorithm.ES384,
            JWSAlgorithm.ES512
        )
    }
}
