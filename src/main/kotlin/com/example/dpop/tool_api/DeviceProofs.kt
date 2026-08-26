package com.example.dpop.tool_api

import com.example.dpop.tool_spi.DevicePublicKey

/** What a controller may learn from a validated device-proof - never the raw JWK it was signed with. */
data class VerifiedDeviceProof(val publicKey: DevicePublicKey, val accessMeans: String)

/**
 * Verifies device-binding proofs (typ="device-proof+jwt") for auth-device/enroll-device
 * (docs/03-tool-architektur.md). Implemented directly by the orchestrator's `DeviceProofValidator`
 * - the crypto/replay-protection machinery stays there, only the verified, opaque result crosses
 * into a method module. A missing or invalid proof throws (mirrors `DpopBindingKeyResolver`'s
 * `DpopValidationException` -> 401, docs/04-orchestrierung.md #5).
 */
interface DeviceProofs {
    fun validate(deviceProof: String?, httpMethod: String, httpUrl: String): VerifiedDeviceProof
}
