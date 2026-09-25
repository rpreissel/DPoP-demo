package com.example.dpop.kcext.webtool;

import com.example.dpop.kcext.KcText;
import org.keycloak.provider.ProviderFactory;

/**
 * One factory per orchestrator toolId. {@link #getId()} IS that toolId (e.g. {@code "auth-password"})
 * - {@code OrchestratorAuthenticator} looks a renderer up via
 * {@code session.getProvider(WebToolRenderer.class, toolId)}, Keycloak's own by-id provider lookup,
 * exactly the registry-by-lookup role {@code NativeAuthenticatorRegistry} plays on the orchestrator
 * side for native authenticators - no separate registry class needed here.
 */
public interface WebToolRendererFactory extends ProviderFactory<WebToolRenderer> {

    /** Display title, shown as this tool's form header and as its label on the method-select page. */
    KcText title();

    /** Short hint, shown under the title - the kc-facade's counterpart to React's {@code ToolMeta.hint}. */
    KcText hint();

    /**
     * The page this tool renders - what its {@code render} passes to {@code createForm}. Known
     * before rendering, so the page gets exactly the texts the Keycloakify theme's component for it
     * uses (docs/ideen/keycloakify-statt-freemarker.md). {@code null} for a tool that shows no page
     * of its own because it completes on activation ({@code enroll-email}).
     */
    String template();
}
