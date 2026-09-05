package com.example.dpop.kcext.webtool;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Base for a tool's factory+renderer pair - stateless (no per-session data), so the factory just
 * hands back itself, the same idiom several {@code Authenticator}/{@code AuthenticatorFactory}
 * pairs in this module already use for {@code create(session)}.
 */
public abstract class AbstractWebToolRendererFactory implements WebToolRendererFactory, WebToolRenderer {

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
