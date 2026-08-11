package com.example.dpop.orchestrator.dpop;

import com.nimbusds.jose.jwk.JWK;

import java.time.Instant;

public record DpopProof(
        String token,
        JWK publicKey,
        String jti,
        String htm,
        String htu,
        Instant issuedAt,
        String nonce
) {
}
