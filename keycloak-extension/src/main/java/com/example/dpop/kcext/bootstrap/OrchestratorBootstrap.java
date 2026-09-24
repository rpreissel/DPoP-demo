package com.example.dpop.kcext.bootstrap;

import org.keycloak.provider.Provider;

/**
 * Was diese Extension beim Start von Keycloak selbst einrichtet, bevor es irgendein Realm des
 * Orchestrators gibt - die Arbeit steckt ganz in der Factory ({@code postInit}), der Provider selbst
 * hat nichts zu tun. Eine eigene SPI statt eines Anhaengsels an eine fremde Factory, damit die
 * Konfiguration ihren eigenen, sprechenden Namen bekommt ({@code spi-orchestrator-bootstrap-...}).
 */
public interface OrchestratorBootstrap extends Provider {
}
