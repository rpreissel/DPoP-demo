package com.example.dpop.kcext;

import org.keycloak.Config;
import org.keycloak.authentication.RequiredActionFactory;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Stateless, same idiom the {@code webtool} factories use (see
 * {@code AbstractWebToolRendererFactory}) - one shared instance handed back for every session.
 * Realm registration (enabled, {@code defaultAction=false}) happens in a migration, not here.
 */
public class OrchestratorManageMethodsRequiredActionFactory implements RequiredActionFactory {

    private static final OrchestratorManageMethodsRequiredAction INSTANCE = new OrchestratorManageMethodsRequiredAction();

    @Override
    public RequiredActionProvider create(KeycloakSession session) {
        return INSTANCE;
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

    @Override
    public String getId() {
        return OrchestratorManageMethodsRequiredAction.PROVIDER_ID;
    }

    @Override
    public String getDisplayText() {
        return "Anmeldeverfahren verwalten (Orchestrator)";
    }
}
