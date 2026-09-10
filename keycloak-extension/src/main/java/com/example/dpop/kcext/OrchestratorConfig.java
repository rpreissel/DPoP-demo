package com.example.dpop.kcext;

/**
 * Environment-driven config, same convention as auth-sandbox-2's AuthApiClient - a container env
 * var per value, no Keycloak Config.Scope indirection needed for a single shared backend URL.
 */
public final class OrchestratorConfig {

    static final String BASE_URL = env("ORCHESTRATOR_BASE_URL", "http://host.containers.internal:8080");
    static final String PEER_AUTH_ISSUER = env("ORCHESTRATOR_PEER_AUTH_ISSUER", "dpop-demo-keycloak");
    static final String PEER_AUTH_AUDIENCE = env("ORCHESTRATOR_PEER_AUTH_AUDIENCE", "dpop-demo-orchestrator");
    // Browser-reachable origin of the demo app (App-Kanal frontend) - NOT the same as BASE_URL,
    // which is server-to-server (Keycloak container -> orchestrator container). Used only to build
    // auth-qr/auth-qr-lookup's demo deep link (docs/ideen/qr-login-ueber-app.md #6), so opening it
    // lands the browser directly in this app instead of a fictitious native deep-link scheme.
    // Public: read from webtool.qr, a different package, unlike every other field here.
    public static final String DEMO_APP_BASE_URL = env("DEMO_APP_BASE_URL", "http://localhost:8080");

    private OrchestratorConfig() {
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
