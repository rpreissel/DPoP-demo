package com.example.dpop.kcext;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An account as the orchestrator shows it to Keycloak ({@code KcAccountLookupController}) - read
 * through on every lookup, never stored here (review 2026-09, P-3).
 */
record KcAccount(
        long accountId,
        String username,
        String email,
        boolean emailVerified,
        String firstName,
        String lastName,
        Map<String, String> attributes
) {
    static KcAccount from(JsonNode json) {
        Map<String, String> attributes = new LinkedHashMap<>();
        json.path("attributes").fields().forEachRemaining(e -> attributes.put(e.getKey(), e.getValue().asText()));
        return new KcAccount(
                json.path("accountId").asLong(),
                json.path("username").asText(),
                json.path("email").isNull() ? null : json.path("email").asText(null),
                json.path("emailVerified").asBoolean(false),
                json.path("firstName").asText(null),
                json.path("lastName").asText(null),
                Map.copyOf(attributes)
        );
    }

    List<String> attribute(String name) {
        String value = attributes.get(name);
        return value == null ? List.of() : List.of(value);
    }
}
