package com.example.dpop.kcext.webtool;

import org.keycloak.provider.Provider;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.Spi;

/** Registers {@link WebToolRenderer} as a normal Keycloak provider SPI, same as this module's own {@code AuthenticatorFactory}/{@code ProtocolMapper} SPIs. */
public class WebToolRendererSpi implements Spi {

    @Override
    public boolean isInternal() {
        return false;
    }

    @Override
    public String getName() {
        return "webToolRenderer";
    }

    @Override
    public Class<? extends Provider> getProviderClass() {
        return WebToolRenderer.class;
    }

    @Override
    public Class<? extends ProviderFactory> getProviderFactoryClass() {
        return WebToolRendererFactory.class;
    }
}
