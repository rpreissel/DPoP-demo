package com.example.dpop.kcext;

/**
 * Environment-driven config, same convention as auth-sandbox-2's AuthApiClient - a container env
 * var per value, no Keycloak Config.Scope indirection needed for a single shared backend URL.
 */
final class OrchestratorConfig {

    static final String BASE_URL = env("ORCHESTRATOR_BASE_URL", "http://host.containers.internal:8080");
    static final String PEER_AUTH_ISSUER = env("ORCHESTRATOR_PEER_AUTH_ISSUER", "dpop-demo-keycloak");
    static final String PEER_AUTH_AUDIENCE = env("ORCHESTRATOR_PEER_AUTH_AUDIENCE", "dpop-demo-orchestrator");

    private OrchestratorConfig() {
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
