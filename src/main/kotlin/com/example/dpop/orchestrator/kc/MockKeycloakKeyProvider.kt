package com.example.dpop.orchestrator.kc

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import org.springframework.stereotype.Component

/**
 * The Mock-Keycloak frontend's signing key (docs/ideen/web-keycloak-kanal.md #3, bd
 * DPoP-demo-f9o.9) - demo/test only, never a real trust boundary. One key pair generated fresh
 * per backend startup: the private half is handed to the frontend once (`GET
 * /mock-keycloak/signing-key`, never a real Keycloak capability) so it can sign peer-auth
 * assertions client-side; the public half is what [PeerAuthValidator] itself fetches via
 * `kc.peer-auth.jwks-uri`, which points right back at this same backend's own `/mock-keycloak/
 * .well-known/jwks.json` - the mock signs with the key the validator already trusts, without
 * either side hand-copying key material.
 */
@Component
class MockKeycloakKeyProvider {
    val key = ECKeyGenerator(Curve.P_256)
        .keyID("mock-keycloak-1")
        .algorithm(JWSAlgorithm.ES256)
        .keyUse(KeyUse.SIGNATURE)
        .generate()
}
