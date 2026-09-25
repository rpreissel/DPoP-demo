package com.example.dpop.orchestrator.kc

import com.example.dpop.tool_api.API_V1
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.security.MessageDigest
import java.time.Instant
import java.util.Date
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper

/**
 * Signiert jede Antwort an Keycloak (Review 2026-09, M-9). Die Peer-Auth-Assertion sichert nur die
 * Anfrage; die Antwort entscheidet aber, wer eingeloggt wird (`authData.accountId`). Wer auf dem Hop
 * mitlesen und antworten kann, bestimmte das bisher. Jetzt prueft die Extension die Signatur, bevor
 * sie einer Antwort glaubt (`OrchestratorResponseVerifier`). TLS auf dem Hop ist Umgebung (ADR-35,
 * Phase G) - die Echtheit der Antwort ist Kern.
 *
 * Die Signatur steht im Header [HEADER] als JWS und bindet:
 * - `req`: die `jti` der Anfrage-Assertion - eine aufgezeichnete Antwort passt zu keiner anderen Anfrage;
 * - `status` und `body_sha256`: genau diese Antwort;
 * - `iss`/`aud`: spiegelbildlich zur Anfrage (Aussteller der Anfrage = Empfaenger der Antwort);
 * - `iat`/`exp`: kurzlebig.
 *
 * Eigener Schluessel ([PURPOSE]), eigenes JWKS ([KeycloakResponseJwksController]) - nicht der
 * Schluessel einer Client-Anmeldung (ein Schluessel je Zweck, S-3).
 */
@Component
class KeycloakResponseSigner(repository: NodeSigningKeyRepository) {
    private val nodeKeys = NodeKeys(repository)

    private fun key(): ECKey = nodeKeys.keyFor(PURPOSE, KEY_ID_PREFIX)

    fun publicKey(): ECKey = key().toPublicJWK()

    fun sign(request: JWTClaimsSet, status: Int, body: ByteArray): String {
        val key = key()
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer(request.audience.singleOrNull())
            .audience(request.issuer)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(TTL_SECONDS)))
            .claim("req", request.jwtid)
            .claim("status", status)
            .claim("body_sha256", Base64URL.encode(MessageDigest.getInstance("SHA-256").digest(body)).toString())
            .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).type(JOSEObjectType(TYPE)).build(), claims)
        jwt.sign(ECDSASigner(key))
        return jwt.serialize()
    }

    companion object {
        const val HEADER = "Orchestrator-Response-Signature"
        const val TYPE = "orchestrator-response+jwt"
        const val PURPOSE = "keycloak-response"
        private const val KEY_ID_PREFIX = "orchestrator-response"
        private const val TTL_SECONDS = 60L
    }
}

/**
 * Haengt die Signatur an jede Antwort auf eine Peer-Auth-Anfrage - erkannt an der Assertion selbst
 * (`channel_anchor`), nicht an einer Liste von Pfaden: sie gilt fuer die Kanal-, Tool- und
 * Passwort-Endpunkte gleichermassen, und ein neuer Endpunkt kann sie nicht vergessen.
 */
@Component
class KeycloakResponseSigningFilter(private val signer: KeycloakResponseSigner) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = peerAuthClaims(request) == null

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val claims = checkNotNull(peerAuthClaims(request))
        val wrapped = ContentCachingResponseWrapper(response)
        try {
            chain.doFilter(request, wrapped)
        } finally {
            wrapped.setHeader(KeycloakResponseSigner.HEADER, signer.sign(claims, wrapped.status, wrapped.contentAsByteArray))
            wrapped.copyBodyToResponse()
        }
    }

    /** Die Claims einer Peer-Auth-Assertion im Authorization-Header, sonst `null` (hier nur gelesen - geprueft wird sie am Endpunkt). */
    private fun peerAuthClaims(request: HttpServletRequest): JWTClaimsSet? {
        val token = request.getHeader("Authorization")?.removePrefix("Bearer ")?.trim() ?: return null
        val claims = runCatching { SignedJWT.parse(token).jwtClaimsSet }.getOrNull() ?: return null
        return claims.takeIf { it.getClaim("channel_anchor") != null && it.jwtid != null }
    }
}

@RestController
@Tag(name = "Keycloak-Kanal", description = "Oeffentlicher Schluessel, gegen den Keycloak die Antworten des Orchestrators prueft")
class KeycloakResponseJwksController(private val signer: KeycloakResponseSigner) {

    @GetMapping(RESPONSE_JWKS_PATH)
    @Operation(operationId = "keycloakResponseJwks", summary = "Public Key der Antwortsignatur (Header Orchestrator-Response-Signature)")
    fun jwks(): Map<String, Any> = JWKSet(signer.publicKey()).toJSONObject()
}

const val RESPONSE_JWKS_PATH = "$API_V1/kc/response-jwks/.well-known/jwks.json"
