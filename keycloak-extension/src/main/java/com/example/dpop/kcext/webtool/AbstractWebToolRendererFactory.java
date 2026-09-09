package com.example.dpop.kcext.webtool;

import com.fasterxml.jackson.databind.JsonNode;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Base for a tool's factory+renderer pair - stateless (no per-session data), so the factory just
 * hands back itself, the same idiom several {@code Authenticator}/{@code AuthenticatorFactory}
 * pairs in this module already use for {@code create(session)}.
 */
public abstract class AbstractWebToolRendererFactory implements WebToolRendererFactory, WebToolRenderer {

    /**
     * Raw JSON of {@code demo.persons} (all seeded demo personas - orchestrator's
     * {@code ToolControllerSupport.demoInfo}, tool_spi/Demo.kt's {@code DEMO_PERSONS}), or the
     * string {@code "null"} when absent. Passed straight to {@code demo-person-picker.ftl}'s
     * macro, which embeds it as a JS array literal - not tool-specific, so this lives on the
     * shared base rather than being repeated in every renderer that offers a persona picker.
     */
    protected static String demoPersonsJson(WebToolRenderContext ctx) {
        JsonNode persons = ctx.demo().get("persons");
        return persons != null ? persons.toString() : "null";
    }

    @Override
    public WebToolRenderer create(KeycloakSession session) {
        return this;
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }
}
