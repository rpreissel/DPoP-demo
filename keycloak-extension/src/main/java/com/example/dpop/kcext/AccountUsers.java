package com.example.dpop.kcext;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.StorageId;

/**
 * The one way this extension resolves an orchestrator account to its Keycloak user. The user IS the
 * account, read through the federation (review 2026-09, P-3) - so the lookup is by id,
 * {@code f:<component>:<accountId>}, a single read. No attribute search and no conflict check:
 * without user mirrors nothing can carry the same account twice (review 2026-09, S-2).
 */
public final class AccountUsers {

    public static final String ACCOUNT_ID_ATTRIBUTE = "orchestratorAccountId";

    private AccountUsers() {
    }

    /** The account's Keycloak user, or {@code null} if there is no such account. */
    public static UserModel findByAccountId(KeycloakSession session, RealmModel realm, String accountId) {
        return OrchestratorStorageProviderFactory.componentIn(realm)
                .map(component -> session.users().getUserById(realm, StorageId.keycloakId(component, accountId)))
                .orElse(null);
    }
}
