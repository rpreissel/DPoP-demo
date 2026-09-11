package com.example.dpop.kcext;

import org.keycloak.Config;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.storage.UserStorageProviderFactory;

/**
 * Factory for {@link OrchestratorPasswordStorageProvider} - holds the long-lived
 * {@link OrchestratorClient} (one HTTP client + signer per factory instance, not re-created per
 * provider instance, same convention as {@link OrchestratorAuthenticator}'s own long-lived client).
 */
public class OrchestratorPasswordStorageProviderFactory implements UserStorageProviderFactory<OrchestratorPasswordStorageProvider> {

    public static final String PROVIDER_ID = "orchestrator-password";

    private OrchestratorClient client;

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getHelpText() {
        return "Delegates password verify/change to the orchestrator's own auth_password store "
                + "- no password is ever stored in Keycloak itself, "
                + "same principle as an LDAP federation provider delegating to its directory.";
    }

    @Override
    public OrchestratorPasswordStorageProvider create(KeycloakSession session, ComponentModel model) {
        return new OrchestratorPasswordStorageProvider(client);
    }

    @Override
    public void init(Config.Scope config) {
        client = new OrchestratorClient(
                OrchestratorConfig.BASE_URL, OrchestratorConfig.PEER_AUTH_ISSUER, OrchestratorConfig.PEER_AUTH_AUDIENCE
        );
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }
}
