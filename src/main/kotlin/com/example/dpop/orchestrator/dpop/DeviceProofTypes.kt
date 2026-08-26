package com.example.dpop.orchestrator.dpop

import com.example.dpop.tool_api.DevicePublicKey
import com.example.dpop.tool_api.UserVerification
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import java.time.Instant

/**
 * Verified self-signed proof from an account-bound device key (docs/03-tool-architektur.md,
 * enroll-device/auth-device) - structurally a DPoP-style proof (own jwk in header, ES256,
 * htm/htu/iat/jti) but with typ="device-proof+jwt" and its own long-lived credential lifecycle,
 * never the per-channel DPoP key ([DpopValidator]).
 */
data class DeviceProof(
    val jwk: JWK,
    val thumbprint: String,
    val userVerification: UserVerification,
    val jti: String,
    val issuedAt: Instant
) {
    /** Crosses into module code as plain strings ([DevicePublicKey]) - modules never see a raw JWK. */
    fun toDevicePublicKey(): DevicePublicKey {
        val ecKey = jwk as? ECKey ?: throw DpopValidationException("Unsupported key type: ${jwk.keyType}")
        return DevicePublicKey(
            kty = ecKey.keyType.value,
            crv = ecKey.curve.name,
            x = ecKey.x.toString(),
            y = ecKey.y.toString(),
            thumbprint = thumbprint
        )
    }
}
