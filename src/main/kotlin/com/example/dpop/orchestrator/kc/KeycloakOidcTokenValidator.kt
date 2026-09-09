package com.example.dpop.orchestrator.kc

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.text.ParseException
import java.time.Instant
import java.net.URI

class OidcTokenValidationException(message: String) : RuntimeException(message)

/**
 * Verifies a real, Keycloak-issued OIDC AccessToken (the Web channel's own, from the standard
 * browser authorization_code login) against Keycloak's own realm signing key - a completely
 * different key/purpose than [PeerAuthValidator] (which verifies Keycloak's short-lived
 * per-request peer-auth assertions, signed by an ephemeral per-node key,
 * [PeerAuthSigningKey]-equivalent on the Keycloak side, never the realm's own token-signing key).
 *
 * Demo-only read path (see `application-keycloak.yml`'s own comment on `kc.oidc`): the Web-Kanal
 * demo UI's Journey-Log tab is the one caller, presenting the browser's own AccessToken directly -
 * this is a debug convenience, not part of the production kc-facade contract that otherwise never
 * lets the browser talk to the orchestrator.
 */
@Component
@Profile("keycloak")
class KeycloakOidcTokenValidator(
    @Value("\${kc.oidc.certs-uri}") certsUri: String,
    @Value("\${kc.oidc.jwks-cache-ttl-seconds:600}") cacheTtlSeconds: Long,
    @Value("\${kc.oidc.issuer}") private val expectedIssuer: String
) {
    // Same fetch-and-cache shape as the peer-auth JWKS client, just pointed at Keycloak's real
    // realm signing key instead of the ephemeral per-node peer-auth one - no reason to duplicate
    // that logic in a second class.
    private val jwkSource = KeycloakJwkSource(certsUri, cacheTtlSeconds)

    /** The orchestrator accountId this token's `orchestrator_account_id` claim names (a built-in Keycloak "User Attribute" mapper reading the same `orchestratorAccountId` user attribute account-sync already writes) - throws if the token doesn't check out or was never minted for a synced account. */
    fun accountIdOf(bearerToken: String?): Long {
        if (bearerToken.isNullOrBlank()) throw OidcTokenValidationException("Missing bearer token")

        val signedJWT = try {
            SignedJWT.parse(bearerToken)
        } catch (e: ParseException) {
            throw OidcTokenValidationException("Invalid token format")
        }

        val kid = signedJWT.header.keyID ?: throw OidcTokenValidationException("Token is missing kid")
        val jwk = jwkSource.find(kid) ?: throw OidcTokenValidationException("Unknown signing key id: $kid")
        verifySignature(signedJWT, jwk)

        val claims = try {
            signedJWT.jwtClaimsSet
        } catch (e: ParseException) {
            throw OidcTokenValidationException("Invalid token claims")
        }
        if (!isExpectedIssuer(claims.issuer)) {
            throw OidcTokenValidationException("Unexpected issuer: ${claims.issuer}")
        }
        val expiresAt = claims.expirationTime?.toInstant() ?: throw OidcTokenValidationException("Token has no exp claim")
        if (expiresAt.isBefore(Instant.now())) throw OidcTokenValidationException("Token expired")

        return claims.getStringClaim("orchestrator_account_id")?.toLongOrNull()
            ?: throw OidcTokenValidationException("Token has no orchestrator_account_id claim - account never synced")
    }

    private fun verifySignature(signedJWT: SignedJWT, jwk: JWK) {
        val valid = try {
            when (jwk) {
                is RSAKey -> signedJWT.verify(RSASSAVerifier(jwk.toRSAPublicKey()))
                is ECKey -> signedJWT.verify(ECDSAVerifier(jwk.toECPublicKey()))
                else -> throw OidcTokenValidationException("Unsupported key type: ${jwk.keyType}")
            }
        } catch (e: JOSEException) {
            throw OidcTokenValidationException("Failed to verify token signature")
        }
        if (!valid) throw OidcTokenValidationException("Invalid token signature")
    }

    /**
     * Keycloak's local HTTPS setup can expose the same realm issuer with a trailing slash or with
     * the scheme rewritten by the development proxy. Keep the configured issuer authoritative for
     * host and realm path; only allow the scheme alias for localhost.
     */
    private fun isExpectedIssuer(actual: String?): Boolean {
        if (actual == null) return false
        val configured = normalizeIssuer(expectedIssuer)
        val token = normalizeIssuer(actual)
        if (token == configured) return true

        val configuredUri = runCatching { URI(configured) }.getOrNull() ?: return false
        val tokenUri = runCatching { URI(token) }.getOrNull() ?: return false
        return configuredUri.host == "localhost" &&
            tokenUri.host == configuredUri.host &&
            tokenUri.port == configuredUri.port &&
            tokenUri.path == configuredUri.path &&
            tokenUri.scheme in setOf("http", "https") &&
            configuredUri.scheme in setOf("http", "https")
    }

    private fun normalizeIssuer(issuer: String): String = issuer.trimEnd('/')
}
