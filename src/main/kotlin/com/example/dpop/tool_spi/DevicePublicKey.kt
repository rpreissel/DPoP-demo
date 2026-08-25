package com.example.dpop.tool_spi

/**
 * The public half of a device-binding key pair, already verified by the orchestrator's
 * DeviceProofValidator (signature + replay + htm/htu/iat checked at the call site) before it
 * ever reaches a module - modules never parse a raw JWK or proof JWT themselves
 * (docs/03-tool-architektur.md #2). Crosses the module boundary as plain strings, same reasoning
 * as [EnrollmentRef]: a module must not depend on orchestrator-internal JWK/crypto types.
 */
data class DevicePublicKey(
    val kty: String,
    val crv: String,
    val x: String,
    val y: String,
    val thumbprint: String
)
