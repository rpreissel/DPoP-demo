package com.example.dpop.kcext;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;

/**
 * This node's peer-auth signing key (docs/ideen/web-keycloak-kanal.md #3: "ein Schluesselpaar pro
 * Client, nicht pro Nutzer, nicht pro Session"). One key pair generated once per JVM, mirroring
 * MockKeycloakKeyProvider on the orchestrator side, but here on the real Keycloak side signing for
 * real instead of being handed to a browser. A restart rotates the key; the orchestrator's
 * KeycloakJwkSource re-fetches on an unknown kid, so that alone never breaks anything running.
 *
 * A single static holder is enough for the one-node docker-compose setup this extension targets -
 * a real multi-node deployment would need this persisted/shared instead.
 */
final class PeerAuthSigningKey {

    static final ECKey KEY = generate();

    private PeerAuthSigningKey() {
    }

    private static ECKey generate() {
        try {
            return new ECKeyGenerator(Curve.P_256)
                    .keyID("kc-ext-" + System.currentTimeMillis())
                    .algorithm(JWSAlgorithm.ES256)
                    .keyUse(KeyUse.SIGNATURE)
                    .generate();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Failed to generate peer-auth signing key", e);
        }
    }
}
