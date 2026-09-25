package com.example.dpop.kcext;

import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.ReadOnlyException;
import org.keycloak.storage.StorageId;
import org.keycloak.storage.adapter.AbstractUserAdapterFederatedStorage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A Keycloak user that IS the orchestrator account, read through - not a copy of it (review 2026-09,
 * P-3). Username, email, names and the person attributes behind the token claims come from the
 * account ({@link KcAccount}) and are read-only here: changing them is the orchestrator's job, and a
 * write through Keycloak would be a second way to change an account. What Keycloak keeps for itself
 * (enabled, required actions, consents, its own credentials) lives in its federated storage, keyed by
 * {@link #getId()}.
 *
 * <p>The id is {@code f:<component>:<accountId>} - the account id, never the username. The base
 * class would derive it from the username, which is the email and changes with it; the id is the
 * token's {@code sub} and must outlive any address change.
 */
final class OrchestratorUser extends AbstractUserAdapterFederatedStorage {

    private static final Set<String> ACCOUNT_OWNED = Set.of(
            UserModel.USERNAME, UserModel.EMAIL, UserModel.FIRST_NAME, UserModel.LAST_NAME, UserModel.EMAIL_VERIFIED);

    private final KcAccount account;

    OrchestratorUser(KeycloakSession session, RealmModel realm, ComponentModel storageProviderModel, KcAccount account) {
        super(session, realm, storageProviderModel);
        this.account = account;
    }

    long accountId() {
        return account.accountId();
    }

    @Override
    public String getId() {
        return StorageId.keycloakId(storageProviderModel, String.valueOf(account.accountId()));
    }

    @Override
    public String getUsername() {
        return account.username();
    }

    @Override
    public void setUsername(String username) {
        throw readOnly(UserModel.USERNAME);
    }

    @Override
    public boolean isEmailVerified() {
        return account.emailVerified();
    }

    @Override
    public void setEmailVerified(boolean verified) {
        throw readOnly(UserModel.EMAIL_VERIFIED);
    }

    @Override
    public String getFirstAttribute(String name) {
        List<String> values = accountValues(name);
        return values != null ? (values.isEmpty() ? null : values.get(0)) : super.getFirstAttribute(name);
    }

    @Override
    public Stream<String> getAttributeStream(String name) {
        List<String> values = accountValues(name);
        return values != null ? values.stream() : super.getAttributeStream(name);
    }

    @Override
    public Map<String, List<String>> getAttributes() {
        Map<String, List<String>> all = new HashMap<>(super.getAttributes());
        ACCOUNT_OWNED.forEach(name -> all.put(name, accountValues(name)));
        account.attributes().forEach((name, value) -> all.put(name, List.of(value)));
        return all;
    }

    @Override
    public void setSingleAttribute(String name, String value) {
        if (isAccountOwned(name)) throw readOnly(name);
        super.setSingleAttribute(name, value);
    }

    @Override
    public void setAttribute(String name, List<String> values) {
        if (isAccountOwned(name)) throw readOnly(name);
        super.setAttribute(name, values);
    }

    @Override
    public void removeAttribute(String name) {
        if (isAccountOwned(name)) throw readOnly(name);
        super.removeAttribute(name);
    }

    /** The account's values for [name], or {@code null} when the account does not own it (then Keycloak's own storage answers). */
    private List<String> accountValues(String name) {
        return switch (name) {
            case UserModel.USERNAME -> List.of(account.username());
            case UserModel.EMAIL -> account.email() == null ? List.of() : List.of(account.email());
            case UserModel.EMAIL_VERIFIED -> List.of(String.valueOf(account.emailVerified()));
            case UserModel.FIRST_NAME -> account.firstName() == null ? List.of() : List.of(account.firstName());
            case UserModel.LAST_NAME -> account.lastName() == null ? List.of() : List.of(account.lastName());
            default -> account.attributes().containsKey(name) ? account.attribute(name) : null;
        };
    }

    private boolean isAccountOwned(String name) {
        return ACCOUNT_OWNED.contains(name) || account.attributes().containsKey(name);
    }

    private static ReadOnlyException readOnly(String name) {
        return new ReadOnlyException("'" + name + "' belongs to the orchestrator account and is read-only in Keycloak");
    }
}
