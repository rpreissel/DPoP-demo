package com.example.dpop.kcext.grant;

import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review 2026-09-26, F-1: only the orchestrator's own confidential client may call the account-token
 * grant - never the public browser client, never any other client of the realm.
 */
class AccountTokenGrantClientTest {

    private static ClientModel client(boolean publicClient, Map<String, String> attributes) {
        return (ClientModel) Proxy.newProxyInstance(
                ClientModel.class.getClassLoader(), new Class<?>[]{ClientModel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isPublicClient" -> publicClient;
                    case "getAttribute" -> attributes.get((String) args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void theOrchestratorsConfidentialClientMayCallIt() {
        assertTrue(AccountTokenGrantClients.isAllowed(
                client(false, Map.of(AccountTokenGrantClients.ALLOWED_CLIENT_ATTRIBUTE, "true"))));
    }

    @Test
    void anyOtherConfidentialClientMayNot() {
        assertFalse(AccountTokenGrantClients.isAllowed(client(false, Map.of())));
    }

    @Test
    void aPublicClientMayNotEvenWithTheAttribute() {
        assertFalse(AccountTokenGrantClients.isAllowed(
                client(true, Map.of(AccountTokenGrantClients.ALLOWED_CLIENT_ATTRIBUTE, "true"))));
    }

    @Test
    void noClientMayNot() {
        assertFalse(AccountTokenGrantClients.isAllowed(null));
    }
}
