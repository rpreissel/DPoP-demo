package com.example.dpop.kcext;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.util.List;
import java.util.stream.Stream;

/**
 * The one way this extension resolves an orchestrator account to its Keycloak user: the user
 * carrying {@code orchestratorAccountId}. Logins, account tokens and public-key credentials all go
 * through here.
 *
 * <p>More than one carrier is a conflict, not a choice: picking the first would hand either user's
 * session this account's tokens, depending on nothing but storage order (review 2026-09, S-2). It is
 * refused with an {@link IllegalStateException}; the orchestrator's account sync never creates a
 * second carrier.
 */
public final class AccountUsers {

    public static final String ACCOUNT_ID_ATTRIBUTE = "orchestratorAccountId";

    private AccountUsers() {
    }

    /** The account's Keycloak user, or {@code null} if it has none yet. */
    public static UserModel findByAccountId(KeycloakSession session, RealmModel realm, String accountId) {
        try (Stream<UserModel> matches = session.users()
                .searchForUserByUserAttributeStream(realm, ACCOUNT_ID_ATTRIBUTE, accountId)) {
            List<UserModel> users = matches.limit(2).toList();
            if (users.size() > 1) {
                throw new IllegalStateException(
                        "More than one Keycloak user carries " + ACCOUNT_ID_ATTRIBUTE + "=" + accountId);
            }
            return users.isEmpty() ? null : users.get(0);
        }
    }
}
