package com.example.dpop.orchestrator.kc

import com.example.dpop.tool_api.htuMatches
import com.example.dpop.orchestrator.dpop.DpopReplayProtectionService
import com.example.dpop.orchestrator.dpop.DpopValidationException
import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.text.ParseException
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Verifies the signed JWT Keycloak sends on every kc-facade request (docs/12-entscheidungen.md
 * ADR-7) - one assertion per request instead of an access token plus a
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
        // Explicit type, so no other JWT signed with the same key - a token of some other purpose -
        // is ever read as a peer-auth assertion (review 2026-09-26, F-10; RFC 8725 3.11).
        if (header.type != ASSERTION_TYPE) {
            throw PeerAuthValidationException("Peer-auth assertion must have typ=${ASSERTION_TYPE.type}")
        }
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
            // Shared single-use store (docs/12-entscheidungen.md ADR-7) - reported under this
            // validator's own exception type so callers only ever catch PeerAuthValidationException.
            throw PeerAuthValidationException("Peer-auth assertion replay detected", e)
        }

        return PeerAuthAssertion(jti, issuedAt, channelAnchor, claims.subject)
    }

    private fun validateSignature(signedJWT: SignedJWT, jwk: JWK) {
        try {
            val valid = when (jwk) {
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
        if (htu == null || !htuMatches(htu, httpUrl)) {
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


    companion object {
        private val SUPPORTED_ALGORITHMS: Set<JWSAlgorithm> = setOf(JWSAlgorithm.ES256)

        /** The `typ` the extension's PeerAuthAssertionSigner sets. */
        val ASSERTION_TYPE = JOSEObjectType("peer-auth+jwt")
    }
}
