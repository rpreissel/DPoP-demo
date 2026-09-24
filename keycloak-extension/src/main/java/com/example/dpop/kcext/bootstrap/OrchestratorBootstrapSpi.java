package com.example.dpop.kcext.bootstrap;

import org.keycloak.provider.Provider;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.Spi;

/** Registriert {@link OrchestratorBootstrap} als normale Keycloak-SPI, wie {@code WebToolRendererSpi}. */
public class OrchestratorBootstrapSpi implements Spi {

    @Override
    public boolean isInternal() {
        return false;
    }

    @Override
    public String getName() {
        return "orchestrator-bootstrap";
    }

    @Override
    public Class<? extends Provider> getProviderClass() {
        return OrchestratorBootstrap.class;
    }

    @Override
    public Class<? extends ProviderFactory> getProviderFactoryClass() {
        return OrchestratorBootstrapFactory.class;
    }
}
