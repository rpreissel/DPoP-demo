package com.example.dpop.tool_spi

/**
 * The public half of a device-binding key pair, already verified by the time a module receives
 * it - modules never parse a raw JWK or proof JWT themselves. Crosses the module boundary as
 * plain strings, the JWK fields of an EC public key.
 */
data class DevicePublicKey(
    val kty: String,
    val crv: String,
    val x: String,
    val y: String,
    val thumbprint: String
)
