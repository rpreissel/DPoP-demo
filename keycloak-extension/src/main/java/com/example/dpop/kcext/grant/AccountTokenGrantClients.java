package com.example.dpop.kcext.grant;

import org.keycloak.models.ClientModel;

/**
 * Which clients may call {@link AccountTokenGrantType}: only a confidential client the realm names
 * for it with {@link #ALLOWED_CLIENT_ATTRIBUTE} (keycloak-migrations V4 sets it on the orchestrator's
 * own client). Without the check every client of the realm - the public browser client included -
 * could call the grant, and the per-account assertion would be the only gate (review 2026-09-26, F-1).
 */
public final class AccountTokenGrantClients {

    public static final String ALLOWED_CLIENT_ATTRIBUTE = "dpop-demo.account-token-grant";

    private AccountTokenGrantClients() {
    }

    /** A public client can prove nothing about itself, so the attribute alone is never enough. */
    public static boolean isAllowed(ClientModel client) {
        return client != null && !client.isPublicClient() && "true".equals(client.getAttribute(ALLOWED_CLIENT_ATTRIBUTE));
    }
}
