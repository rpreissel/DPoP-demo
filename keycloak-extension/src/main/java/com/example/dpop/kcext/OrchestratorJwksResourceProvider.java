package com.example.dpop.kcext;

import com.nimbusds.jose.jwk.JWKSet;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.keycloak.services.resource.RealmResourceProvider;

/**
 * Publishes this node's peer-auth public key at
 * {@code /realms/{realm}/orchestrator-jwks/.well-known/jwks.json} - what the orchestrator's
 * {@code kc.peer-auth.jwks-uri} points at (docs/ideen/web-keycloak-kanal.md #3). A real Keycloak
 * realm's own {@code /protocol/openid-connect/certs} exists for token signing keys, which is a
 * different keypair for a different purpose - this extension deliberately publishes its own,
 * client-scoped key here rather than overloading the realm's token-signing keys.
 */
public class OrchestratorJwksResourceProvider implements RealmResourceProvider {

    @Override
    public Object getResource() {
        return this;
    }

    @GET
    @Path(".well-known/jwks.json")
    @Produces(MediaType.APPLICATION_JSON)
    public String jwks() {
        return new JWKSet(PeerAuthSigningKey.KEY.toPublicJWK()).toString();
    }

    @Override
    public void close() {
    }
}
