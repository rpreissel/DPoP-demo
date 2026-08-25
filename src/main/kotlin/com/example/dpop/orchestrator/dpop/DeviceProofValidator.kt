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

/**
 * Verifies device-binding proofs (typ="device-proof+jwt") for enroll-device/auth-device. Deliberately
 * a sibling of [DpopValidator], not a generalization of it: both verify a self-signed ES256 JWT with
 * an embedded public jwk, but they authenticate different things (a per-channel key vs. a long-lived,
 * account-bound device credential) and must never accept each other's proofs - keeping the ~60 lines
 * of parse/signature/claims checks duplicated here (rather than forcing DpopValidator to become
 * generic over `typ`) keeps that distinction enforced by a hardcoded check in each class instead of a
 * caller-supplied parameter that could be passed wrong (docs/08-projektrahmen.md A11).
 */
@Component
class DeviceProofValidator(
    private val jwkThumbprintService: JwkThumbprintService,
    private val replayProtectionService: DpopReplayProtectionService,
    @Value("\${dpop.proof.max-clock-skew-seconds:30}") private val maxClockSkewSeconds: Long,
    @Value("\${dpop.proof.max-age-seconds:120}") private val maxProofAgeSeconds: Long
) {

    fun validate(deviceProof: String?, httpMethod: String, httpUrl: String): DeviceProof {
        if (deviceProof.isNullOrBlank()) {
            throw DpopValidationException("Missing device proof")
        }

        val signedJWT: SignedJWT = try {
            SignedJWT.parse(deviceProof)
        } catch (e: ParseException) {
            throw DpopValidationException("Invalid device proof format", e)
        }

        val header = signedJWT.header
        validateHeader(header)

        val jwk = header.jwk
        if (jwk == null || jwk.isPrivate) {
            throw DpopValidationException("Device proof must contain a public JWK")
        }

        val claims: JWTClaimsSet = try {
            signedJWT.jwtClaimsSet
        } catch (e: ParseException) {
            throw DpopValidationException("Invalid device proof claims", e)
        }

        validateSignature(signedJWT, jwk)
        validateClaims(claims, httpMethod, httpUrl)
        val accessMeans = validateAccessMeans(claims)

        val thumbprint = jwkThumbprintService.computeThumbprint(jwk)
        val issuedAt = claims.issueTime.toInstant()
        val replayKeyExpiresAt = issuedAt.plus(maxProofAgeSeconds + maxClockSkewSeconds, ChronoUnit.SECONDS)
        replayProtectionService.validateAndStore(thumbprint, claims.getJWTID(), replayKeyExpiresAt)

        return DeviceProof(jwk, thumbprint, accessMeans, claims.getJWTID(), issuedAt)
    }

    private fun validateHeader(header: JWSHeader) {
        if (header.type == null || header.type.type == null
            || !DEVICE_PROOF_JWT_TYPE.equals(header.type.type, ignoreCase = true)) {
            throw DpopValidationException("Device proof must have type 'device-proof+jwt'")
        }
        if (header.algorithm !in SUPPORTED_ALGORITHMS) {
            throw DpopValidationException("Unsupported device proof algorithm: ${header.algorithm}")
        }
    }

    private fun validateSignature(signedJWT: SignedJWT, jwk: JWK) {
        try {
            val valid: Boolean = when (jwk) {
                is ECKey -> signedJWT.verify(ECDSAVerifier(jwk.toECPublicKey()))
                else -> throw DpopValidationException("Unsupported key type: ${jwk.keyType}")
            }
            if (!valid) {
                throw DpopValidationException("Invalid device proof signature")
            }
        } catch (e: JOSEException) {
            throw DpopValidationException("Failed to verify device proof signature", e)
        }
    }

    private fun validateClaims(claims: JWTClaimsSet, httpMethod: String, httpUrl: String) {
        try {
            val htm = claims.getStringClaim("htm")
            if (htm == null || !htm.equals(httpMethod, ignoreCase = true)) {
                throw DpopValidationException("Device proof htm claim does not match request method")
            }

            val htu = claims.getStringClaim("htu")
            if (htu == null) {
                throw DpopValidationException("Device proof htu claim is missing")
            }
            if (!normalizeUrl(htu).equals(normalizeUrl(httpUrl), ignoreCase = true)) {
                throw DpopValidationException("Device proof htu claim does not match request URL")
            }

            val issuedAt = claims.issueTime?.toInstant()
                ?: throw DpopValidationException("Device proof iat claim is missing")
            val now = Instant.now()
            if (issuedAt.isAfter(now.plus(maxClockSkewSeconds, ChronoUnit.SECONDS))) {
                throw DpopValidationException("Device proof iat claim is in the future")
            }
            if (issuedAt.isBefore(now.minus(maxProofAgeSeconds, ChronoUnit.SECONDS))) {
                throw DpopValidationException("Device proof iat claim is too old")
            }

            val jti = claims.getJWTID()
            if (jti.isNullOrBlank()) {
                throw DpopValidationException("Device proof jti claim is missing")
            }
        } catch (e: ParseException) {
            throw DpopValidationException("Invalid device proof claims", e)
        }
    }

    private fun validateAccessMeans(claims: JWTClaimsSet): String {
        val accessMeans = try {
            claims.getStringClaim("accessMeans")
        } catch (e: ParseException) {
            throw DpopValidationException("Invalid device proof claims", e)
        }
        if (accessMeans !in SUPPORTED_ACCESS_MEANS) {
            throw DpopValidationException("Unsupported or missing accessMeans claim: $accessMeans")
        }
        return accessMeans
    }

    private fun normalizeUrl(url: String): String {
        val queryIndex = url.indexOf('?')
        val withoutQuery = if (queryIndex >= 0) url.substring(0, queryIndex) else url
        val fragmentIndex = withoutQuery.indexOf('#')
        return if (fragmentIndex >= 0) withoutQuery.substring(0, fragmentIndex) else withoutQuery
    }

    companion object {
        private const val DEVICE_PROOF_JWT_TYPE = "device-proof+jwt"
        private val SUPPORTED_ALGORITHMS: Set<JWSAlgorithm> = setOf(
            JWSAlgorithm.ES256,
            JWSAlgorithm.ES384,
            JWSAlgorithm.ES512
        )
        private val SUPPORTED_ACCESS_MEANS: Set<String> = setOf("pin", "biometric")
    }
}
