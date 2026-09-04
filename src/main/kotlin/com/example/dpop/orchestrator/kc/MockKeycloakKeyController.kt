package com.example.dpop.orchestrator.kc

import com.nimbusds.jose.jwk.JWKSet
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Backs the Mock-Keycloak frontend (bd DPoP-demo-f9o.9) - not a real Keycloak endpoint, and never
 * meant to model one; a real Keycloak realm publishes its own certs endpoint, this one only exists
 * because this demo needs SOME place both the frontend (signing) and [PeerAuthValidator]
 * (verifying, via `kc.peer-auth.jwks-uri`) can agree on the same key without hand-copying it.
 */
@RestController
@RequestMapping("/mock-keycloak")
@Tag(name = "Mock Keycloak", description = "Demo-only signing key for the Mock-Keycloak frontend - not a real Keycloak endpoint")
class MockKeycloakKeyController(private val keyProvider: MockKeycloakKeyProvider) {

    @GetMapping(".well-known/jwks.json")
    @Operation(summary = "The mock's public key, in the shape kc.peer-auth.jwks-uri expects")
    fun jwks(): Map<String, Any> = JWKSet(keyProvider.key.toPublicJWK()).toJSONObject()

    @GetMapping("signing-key")
    @Operation(
        summary = "The mock's PRIVATE signing key - demo/test only",
        description = "Real Keycloak never exposes this; the Mock-Keycloak frontend fetches it once " +
            "to sign peer-auth assertions client-side, standing in for the real Authenticator SPI plugin."
    )
    fun signingKey(): Map<String, Any> = keyProvider.key.toJSONObject()
}
