package com.example.dpop.kcext;

import org.keycloak.Config;
import org.keycloak.authentication.RequiredActionFactory;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Eine Instanz pro Session, wie bei den Authenticator-Factories: der Client haengt an der
 * Realm-Konfiguration ({@link OrchestratorSettings}), die erst mit der Session feststeht - eine
 * geteilte statische Instanz koennte ihn nicht tragen.
 * Realm registration (enabled, {@code defaultAction=false}) happens in a migration, not here.
 */
public class OrchestratorManageMethodsRequiredActionFactory implements RequiredActionFactory {

    @Override
    public RequiredActionProvider create(KeycloakSession session) {
        return new OrchestratorManageMethodsRequiredAction(OrchestratorSettings.of(session).newClient());
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
