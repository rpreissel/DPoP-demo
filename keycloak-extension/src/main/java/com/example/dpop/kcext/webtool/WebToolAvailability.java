package com.example.dpop.kcext.webtool;

import org.keycloak.models.KeycloakSession;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The Web channel's own declaration of which toolIds it can render, sent to the orchestrator on
 * every {@code upsertChannel} call - the kc-facade's counterpart to the App channel's own
 * {@code availableTools} declaration (frontend/src/tools/registry.ts's {@code knownToolIds}, sent
 * via {@code POST /channels}). Unlike the orchestrator process, this runs IN the same JVM as every
 * registered {@link WebToolRenderer}, so it can just ask Keycloak's own provider registry instead
 * of hardcoding a list anywhere.
 */
public final class WebToolAvailability {

    private WebToolAvailability() {
    }

    public static List<String> renderableToolIds(KeycloakSession session) {
        return session.getKeycloakSessionFactory()
                .getProviderFactoriesStream(WebToolRenderer.class)
                .map(f -> f.getId())
                .collect(Collectors.toList());
    }
}
