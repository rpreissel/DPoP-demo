package com.example.dpop.kcext;

import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public class OrchestratorRestoreDataListenerFactory implements EventListenerProviderFactory {

    public static final String ID = "orchestrator-restore-data";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new OrchestratorRestoreDataListener(session);
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
