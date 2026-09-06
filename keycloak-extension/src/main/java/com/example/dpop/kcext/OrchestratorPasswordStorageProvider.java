package com.example.dpop.kcext;

import org.jboss.logging.Logger;
import org.keycloak.credential.CredentialInput;
import org.keycloak.credential.CredentialInputUpdater;
import org.keycloak.credential.CredentialInputValidator;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.UserStorageProvider;

import java.io.IOException;
import java.util.stream.Stream;

/**
 * Delegates Keycloak's native password credential to the orchestrator's own {@code auth_password}
 * store (docs/ideen/web-keycloak-kanal.md, DPoP-demo-25q) - LDAP-federation-style: no password is
 * ever stored here, every check/change is a live HTTP round-trip. Deliberately NOT a
 * {@link org.keycloak.storage.user.UserLookupProvider} - the local, per-account Keycloak user
 * created/mirrored by {@link OrchestratorAuthenticator}/{@code KeycloakAccountSyncListener} stays
 * exactly as it is; only the "password" credential type is routed here, via
 * {@link UserModel#getFederationLink()} pointing at this provider's component id.
 */
public class OrchestratorPasswordStorageProvider implements UserStorageProvider, CredentialInputValidator, CredentialInputUpdater {

    private static final Logger LOG = Logger.getLogger(OrchestratorPasswordStorageProvider.class);

    private final OrchestratorClient client;

    OrchestratorPasswordStorageProvider(OrchestratorClient client) {
        this.client = client;
    }

    @Override
    public boolean supportsCredentialType(String credentialType) {
        return PasswordCredentialModel.TYPE.equals(credentialType);
    }

    @Override
    public boolean isConfiguredFor(RealmModel realm, UserModel user, String credentialType) {
        // The account always has exactly one authentication journey worth of methods already
        // resolved on the orchestrator side; this provider only ever handles "password", and every
        // federation-linked user is meant to have one (enroll-password ran on the App channel
        // first, see docs/06-ablaeufe.md #4) - a bare true here mirrors how the built-in JPA
        // password provider itself has no cheaper way to answer without a round trip either.
        return supportsCredentialType(credentialType);
    }

    @Override
    public boolean isValid(RealmModel realm, UserModel user, CredentialInput input) {
        if (!supportsCredentialType(input.getType())) return false;
        Long accountId = OrchestratorNotes.accountId(user);
        if (accountId == null) return false;
        try {
            return client.verifyPassword(accountId, input.getChallengeResponse());
        } catch (IOException | InterruptedException e) {
            LOG.warnf(e, "Failed to verify password for account %d", accountId);
            return false;
        }
    }

    @Override
    public boolean updateCredential(RealmModel realm, UserModel user, CredentialInput input) {
        if (!supportsCredentialType(input.getType())) return false;
        Long accountId = OrchestratorNotes.accountId(user);
        if (accountId == null) return false;
        try {
            client.setPassword(accountId, input.getChallengeResponse());
            return true;
        } catch (IOException | InterruptedException e) {
            LOG.warnf(e, "Failed to set password for account %d", accountId);
            return false;
        }
    }

    @Override
    public void disableCredentialType(RealmModel realm, UserModel user, String credentialType) {
        // Password is never disableable on its own (same as the built-in provider) - no-op.
    }

    @Override
    public Stream<String> getDisableableCredentialTypesStream(RealmModel realm, UserModel user) {
        return Stream.empty();
    }

    @Override
    public void close() {
    }
}
