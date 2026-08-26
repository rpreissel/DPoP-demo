package com.example.dpop.tool_api

/**
 * The public half of a device-binding key pair, already verified by the time a module receives
 * it - modules never parse a raw JWK or proof JWT themselves. Crosses the module boundary as
 * plain strings, the JWK fields of an EC public key.
 *
 * @property kty the JWK key type, always `"EC"` for device-binding keys.
 * @property crv the elliptic curve name, e.g. `"P-256"`.
 * @property x the EC public key's x-coordinate, base64url-encoded.
 * @property y the EC public key's y-coordinate, base64url-encoded.
 * @property thumbprint the JWK SHA-256 thumbprint (RFC 7638) of this key - the stable identifier
 * used to look up and compare device enrollments, since [kty]/[crv]/[x]/[y] together are unwieldy
 * as a lookup key.
 */
data class DevicePublicKey(
    val kty: String,
    val crv: String,
    val x: String,
    val y: String,
    val thumbprint: String
)

/**
 * How the user unlocked a device key to produce a proof. The app attests this itself when it
 * creates the proof; the server does not and cannot verify how the unlock happened, only that a
 * valid proof was produced.
 *
 * @property wireValue the value as it appears in the proof JWT's `accessMeans` claim.
 */
enum class UserVerification(val wireValue: String) {
    PIN("pin"),
    BIOMETRIC("biometric");

    companion object {
        fun fromWireValue(value: String?): UserVerification? = entries.find { it.wireValue == value }
    }
}

/**
 * The outcome of a successfully validated device-binding proof.
 *
 * @property publicKey the device key the proof was signed with.
 * @property userVerification how the user unlocked the device key for this proof.
 */
data class VerifiedDeviceProof(val publicKey: DevicePublicKey, val userVerification: UserVerification)

/** Validates device-binding proofs sent by an app client for device-bound tools. */
interface DeviceProofs {
    /**
     * Validates a device-binding proof JWT (`typ="device-proof+jwt"`) against the current request.
     *
     * @param deviceProof the raw proof JWT from the request header, or `null` if absent.
     * @param httpMethod the HTTP method of the current request, e.g. `"POST"` - checked against
     * the proof's own `htm` claim.
     * @param httpUrl the full URL of the current request, e.g. from [buildRequestUrl] - checked
     * against the proof's own `htu` claim.
     * @return the verified device public key and the access means (e.g. `"pin"`, `"biometric"`)
     * the proof was created with.
     * @throws RuntimeException if [deviceProof] is missing, malformed, expired, already used, or
     * does not match [httpMethod]/[httpUrl]; mapped to `401 Unauthorized` by the application's
     * error handling.
     */
    fun validate(deviceProof: String?, httpMethod: String, httpUrl: String): VerifiedDeviceProof
}
