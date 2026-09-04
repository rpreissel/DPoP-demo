package com.example.dpop.orchestrator.api.v1.kc

import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.policy.MethodEvidence
import com.example.dpop.orchestrator.policy.MethodName
import com.example.dpop.orchestrator.session.AcrLevel
import com.example.dpop.orchestrator.session.AmrSource
import com.example.dpop.tool_spi.FactorType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * Signs/verifies [RestoreData] as a compact JWT (docs/ideen/web-keycloak-kanal.md #6) - Keycloak
 * only ever stores and echoes back an opaque token it cannot forge or reattribute to a different
 * UserSession, never the plain accountId/evidence values themselves. The token's `sub` is the
 * `kcSessionId` it was minted for; [decode] refuses anything not bound to the caller's OWN
 * `kcSessionId`, so a leaked or misdirected token can't seed evidence under a session it was never
 * proved for.
 *
 * Symmetric (HS256) and deliberately NOT `MockKeycloakKeyProvider`'s key: that one the mock
 * frontend deliberately RECEIVES so it can act as Keycloak - reusing it here would let anyone
 * holding it forge their own RestoreData too, defeating the whole point of this being an
 * orchestrator-only trust anchor. Generated fresh per boot, same "nothing to check in and forget"
 * reasoning as `dpop.secrets.otp-pepper` - a restart invalidates any RestoreData token still in
 * flight, acceptable for a value that only ever needs to outlive one Keycloak UserSession.
 */
@Component
class RestoreDataCodec {
    private val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun encode(restoreData: RestoreData, kcSessionId: String): String {
        val claims = JWTClaimsSet.Builder()
            .subject(kcSessionId)
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plus(TTL)))
            .claim("accountId", restoreData.accountId)
            .claim("factors", restoreData.evidence?.factors?.map { it.toClaim() })
            .build()
        val jwt = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
        jwt.sign(MACSigner(secret))
        return jwt.serialize()
    }

    /**
     * `null` on ANY problem - invalid signature, expired, or bound to a different `kcSessionId`
     * than the caller's own - never thrown. A bad or stale restore token just means "start fresh,"
     * not a request failure; the caller already proved a real UserSession via the peer-auth
     * assertion this [kcSessionId] came from, so this only ever narrows what gets restored, never
     * what the caller is otherwise allowed to do.
     */
    fun decode(token: String, kcSessionId: String?): RestoreData? {
        if (kcSessionId == null) return null
        return try {
            val jwt = SignedJWT.parse(token)
            if (!jwt.verify(MACVerifier(secret))) return null
            val claims = jwt.jwtClaimsSet
            if (claims.subject != kcSessionId) return null
            if (claims.expirationTime?.before(Date()) != false) return null
            @Suppress("UNCHECKED_CAST")
            val factorsClaim = claims.getClaim("factors") as? List<Map<String, Any?>>
            val factors = factorsClaim?.map { it.toMethodEvidence() }
            RestoreData(
                accountId = (claims.getClaim("accountId") as? Number)?.toLong(),
                evidence = factors?.takeIf { it.isNotEmpty() }?.let { AuthEvidence(it) }
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun MethodEvidence.toClaim(): Map<String, Any?> = buildMap {
        put("method", method.value)
        put("loa", loa.value)
        enrolledUnderAcr?.let { put("enrolledUnderAcr", it.value) }
        if (factorTypes.isNotEmpty()) put("factorTypes", factorTypes.map { it.name })
        // Both carried through verbatim - see MethodEvidence.source's own doc for why this is
        // exactly the field that makes a restore preserve "orchestrator" strength instead of
        // degrading every restored method to a mere kc self-report.
        put("source", source)
        put("amrSourceId", amrSourceId)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.toMethodEvidence(): MethodEvidence = MethodEvidence(
        method = MethodName(this["method"] as String),
        loa = AcrLevel(this["loa"] as String),
        enrolledUnderAcr = (this["enrolledUnderAcr"] as? String)?.let(::AcrLevel),
        factorTypes = (this["factorTypes"] as? List<String>)?.mapNotNull { name ->
            runCatching { FactorType.valueOf(name) }.getOrNull()
        }?.toSet() ?: emptySet(),
        source = this["source"] as? String ?: AmrSource.KEYCLOAK,
        amrSourceId = this["amrSourceId"] as? String ?: this["method"] as String,
    )

    companion object {
        // Generous relative to this demo's other TTLs - a Keycloak UserSession can legitimately
        // outlive a single kc CHANNEL_TTL by a lot; this token's own validity is the backstop that
        // actually bounds how long a restore stays honorable, not the channel it was read from.
        private val TTL: Duration = Duration.ofHours(12)
    }
}
