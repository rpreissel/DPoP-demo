package com.example.dpop.orchestrator.dpop

import com.nimbusds.jose.jwk.JWK
import java.time.Instant

data class DpopProof(
    val token: String,
    val publicKey: JWK,
    val jti: String,
    val htm: String,
    val htu: String,
    val issuedAt: Instant,
    val nonce: String?
)

class DpopValidationException : RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}
