package com.example.dpop.orchestrator.dpop

import com.nimbusds.jose.jwk.JWK
import java.time.Instant

@JvmRecord
data class DpopProof(
    val token: String,
    val publicKey: JWK,
    val jti: String,
    val htm: String,
    val htu: String,
    val issuedAt: Instant,
    val nonce: String?
)
