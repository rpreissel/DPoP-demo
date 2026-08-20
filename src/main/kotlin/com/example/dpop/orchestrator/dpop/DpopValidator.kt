package com.example.dpop.orchestrator.dpop

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
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
import java.util.Date

@Component
class DpopValidator(
    private val jwkThumbprintService: JwkThumbprintService,
    private val replayProtectionService: DpopReplayProtectionService,
    @Value("\${dpop.proof.max-clock-skew-seconds:30}") private val maxClockSkewSeconds: Long,
    @Value("\${dpop.proof.max-age-seconds:120}") private val maxProofAgeSeconds: Long
) {

    fun validate(dpopProof: String?, httpMethod: String, httpUrl: String): DpopProof {
        if (dpopProof.isNullOrBlank()) {
            throw DpopValidationException("Missing DPoP proof")
        }

        val signedJWT: SignedJWT = try {
            SignedJWT.parse(dpopProof)
        } catch (e: ParseException) {
            throw DpopValidationException("Invalid DPoP proof format", e)
        }

        val header = signedJWT.header
        validateHeader(header)

        val jwk = header.jwk
        if (jwk == null || jwk.isPrivate) {
            throw DpopValidationException("DPoP proof must contain a public JWK")
        }

        val claims: JWTClaimsSet = try {
            signedJWT.jwtClaimsSet
        } catch (e: ParseException) {
            throw DpopValidationException("Invalid DPoP claims", e)
        }

        validateSignature(signedJWT, jwk, header.algorithm)
        validateClaims(claims, httpMethod, httpUrl)

        val thumbprint = jwkThumbprintService.computeThumbprint(jwk)
        val issuedAt = claims.issueTime.toInstant()
        val replayKeyExpiresAt = issuedAt.plus(maxProofAgeSeconds + maxClockSkewSeconds, ChronoUnit.SECONDS)
        replayProtectionService.validateAndStore(thumbprint, claims.getJWTID(), replayKeyExpiresAt)

        return try {
            DpopProof(
                dpopProof,
                jwk,
                claims.getJWTID(),
                claims.getStringClaim("htm"),
                claims.getStringClaim("htu"),
                issuedAt,
                claims.getStringClaim("nonce")
            )
        } catch (e: ParseException) {
            throw DpopValidationException("Invalid DPoP claims", e)
        }
    }

    private fun validateHeader(header: JWSHeader) {
        if (header.type == null || header.type.type == null
            || !DPOP_JWT_TYPE.equals(header.type.type, ignoreCase = true)) {
            throw DpopValidationException("DPoP proof must have type 'dpop+jwt'")
        }
        if (header.algorithm !in SUPPORTED_ALGORITHMS) {
            throw DpopValidationException("Unsupported DPoP algorithm: ${header.algorithm}")
        }
    }

    private fun validateSignature(signedJWT: SignedJWT, jwk: JWK, algorithm: JWSAlgorithm) {
        try {
            val valid: Boolean = when (jwk) {
                is ECKey -> signedJWT.verify(ECDSAVerifier(jwk.toECPublicKey()))
                else -> throw DpopValidationException("Unsupported key type: ${jwk.keyType}")
            }
            if (!valid) {
                throw DpopValidationException("Invalid DPoP proof signature")
            }
        } catch (e: JOSEException) {
            throw DpopValidationException("Failed to verify DPoP proof signature", e)
        }
    }

    private fun validateClaims(claims: JWTClaimsSet, httpMethod: String, httpUrl: String) {
        try {
            val htm = claims.getStringClaim("htm")
            if (htm == null || !htm.equals(httpMethod, ignoreCase = true)) {
                throw DpopValidationException("DPoP htm claim does not match request method")
            }

            val htu = claims.getStringClaim("htu")
            if (htu == null) {
                throw DpopValidationException("DPoP htu claim is missing")
            }
            if (!normalizeUrl(htu).equals(normalizeUrl(httpUrl), ignoreCase = true)) {
                throw DpopValidationException("DPoP htu claim does not match request URL")
            }

            val issueTime: Date = claims.issueTime
                ?: throw DpopValidationException("DPoP iat claim is missing")
            val issuedAt = issueTime.toInstant()
            val now = Instant.now()
            if (issuedAt.isAfter(now.plus(maxClockSkewSeconds, ChronoUnit.SECONDS))) {
                throw DpopValidationException("DPoP iat claim is in the future")
            }
            if (issuedAt.isBefore(now.minus(maxProofAgeSeconds, ChronoUnit.SECONDS))) {
                throw DpopValidationException("DPoP iat claim is too old")
            }

            val jti = claims.getJWTID()
            if (jti.isNullOrBlank()) {
                throw DpopValidationException("DPoP jti claim is missing")
            }
        } catch (e: ParseException) {
            throw DpopValidationException("Invalid DPoP claims", e)
        }
    }

    private fun normalizeUrl(url: String): String {
        val queryIndex = url.indexOf('?')
        val withoutQuery = if (queryIndex >= 0) url.substring(0, queryIndex) else url
        val fragmentIndex = withoutQuery.indexOf('#')
        return if (fragmentIndex >= 0) withoutQuery.substring(0, fragmentIndex) else withoutQuery
    }

    companion object {
        private const val DPOP_JWT_TYPE = "dpop+jwt"
        private val SUPPORTED_ALGORITHMS: Set<JWSAlgorithm> = setOf(
            JWSAlgorithm.ES256,
            JWSAlgorithm.ES384,
            JWSAlgorithm.ES512
        )
    }
}
