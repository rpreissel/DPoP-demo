package com.example.dpop.kcext;

import org.jboss.logging.Logger;
import org.keycloak.credential.CredentialInput;
import org.keycloak.credential.CredentialInputUpdater;
import org.keycloak.credential.CredentialInputValidator;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.StorageId;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.user.UserLookupProvider;
import org.keycloak.storage.user.UserQueryMethodsProvider;
import org.keycloak.storage.user.UserRegistrationProvider;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The orchestrator's accounts ARE Keycloak's users - read through on demand, never copied (review
 * 2026-09, P-3; Fahrplan Phase E 26). LDAP-federation-style without import: every lookup asks the
 * orchestrator ({@code KcAccountLookupController}) and wraps the answer as an {@link OrchestratorUser};
 * Keycloak caches it for at most 60 s (component config, V2 migration). There is no mirror to keep
 * in step and no sync that has to touch every account - with 10 million+ accounts, a lookup costs one
 * indexed read on the orchestrator side, a mirror would cost one Admin-API write per change.
 *
 * <p>The password credential is delegated the same way: no password is ever stored here, every
 * check/change is a live HTTP round trip.
 *
 * <p>Searches find exactly one user by exact username, email or account id - never a list: the admin
 * console cannot page through 10 million users, and nothing in a login needs to.
 */
public class OrchestratorStorageProvider implements UserStorageProvider, UserRegistrationProvider,
        UserLookupProvider, UserQueryMethodsProvider, CredentialInputValidator, CredentialInputUpdater {

    private static final Logger LOG = Logger.getLogger(OrchestratorStorageProvider.class);

    private final KeycloakSession session;
    private final ComponentModel model;
    private final OrchestratorClient client;

    OrchestratorStorageProvider(KeycloakSession session, ComponentModel model, OrchestratorClient client) {
        this.session = session;
        this.model = model;
        this.client = client;
    }

    // Lookup ---------------------------------------------------------------------------------

    @Override
    public UserModel getUserById(RealmModel realm, String id) {
        String external = StorageId.externalId(id);
        long accountId;
        try {
            accountId = Long.parseLong(external);
        } catch (NumberFormatException e) {
            return null;
        }
        return wrap(realm, read(() -> client.accountById(accountId)));
    }

    @Override
    public UserModel getUserByUsername(RealmModel realm, String username) {
        return wrap(realm, read(() -> client.accountByUsername(username)));
    }

    @Override
    public UserModel getUserByEmail(RealmModel realm, String email) {
        return wrap(realm, read(() -> client.accountByEmail(email)));
    }

    @Override
    public Stream<UserModel> searchForUserStream(RealmModel realm, Map<String, String> params, Integer firstResult, Integer maxResults) {
        if (firstResult != null && firstResult > 0) return Stream.empty();
        String term = firstNonBlank(params.get(UserModel.SEARCH), params.get(UserModel.USERNAME), params.get(UserModel.EMAIL));
        if (term == null || term.contains("*")) return Stream.empty();
        UserModel byUsername = getUserByUsername(realm, term.trim());
        return byUsername != null ? Stream.of(byUsername) : Stream.ofNullable(getUserByEmail(realm, term.trim()));
    }

    @Override
    public Stream<UserModel> searchForUserByUserAttributeStream(RealmModel realm, String attrName, String attrValue) {
        if (!OrchestratorNotes.USER_ATTR_ACCOUNT_ID.equals(attrName)) return Stream.empty();
        return Stream.ofNullable(getUserById(realm, StorageId.keycloakId(model, attrValue)));
    }

    @Override
    public Stream<UserModel> getGroupMembersStream(RealmModel realm, GroupModel group, Integer firstResult, Integer maxResults) {
        return Stream.empty();
    }

    private UserModel wrap(RealmModel realm, KcAccount account) {
        return account == null ? null : new OrchestratorUser(session, realm, model, account);
    }

    /**
     * An unreachable orchestrator is an error, not "no such user": answering {@code null} would let
     * Keycloak treat a known user as unknown (user_not_found, a failed login counted against no one).
     */
    private static KcAccount read(Lookup lookup) {
        try {
            return lookup.run();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ModelException("Orchestrator account lookup failed", e);
        }
    }

    @FunctionalInterface
    private interface Lookup {
        KcAccount run() throws IOException, InterruptedException;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    // Registration and removal ----------------------------------------------------------------

    @Override
    public UserModel addUser(RealmModel realm, String username) {
        // Users remain local Keycloak users; this provider only participates in their removal.
        return null;
    }

    @Override
    public boolean removeUser(RealmModel realm, UserModel user) {
        // UserStorageManager delegates to local storage after this provider approves removal.
        return true;
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
