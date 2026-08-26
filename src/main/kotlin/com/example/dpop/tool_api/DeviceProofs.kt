package com.example.dpop.tool_api

import com.example.dpop.tool_spi.DevicePublicKey

/** The outcome of a successfully validated device-binding proof. */
data class VerifiedDeviceProof(val publicKey: DevicePublicKey, val accessMeans: String)

/** Validates device-binding proofs sent by an app client for device-bound tools. */
interface DeviceProofs {
    /**
     * Validates a device-binding proof JWT (`typ="device-proof+jwt"`) against the current request.
     *
     * @param deviceProof the raw proof JWT from the request header, or `null` if absent.
     * @param httpMethod the HTTP method of the current request, e.g. `"POST"`.
     * @param httpUrl the full URL of the current request (see [buildRequestUrl]).
     * @return the verified device public key and the access means (e.g. `"pin"`, `"biometric"`)
     * the proof was created with.
     * @throws RuntimeException if [deviceProof] is missing or fails validation; this is mapped to
     * `401 Unauthorized` by the application's error handling.
     */
    fun validate(deviceProof: String?, httpMethod: String, httpUrl: String): VerifiedDeviceProof
}
