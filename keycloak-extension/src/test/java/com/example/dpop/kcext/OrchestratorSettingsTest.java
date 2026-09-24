package com.example.dpop.kcext;

import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Der Peer-Auth-Signaturschluessel entsteht nur in validateConfiguration - also wenn Keycloak die
 * Komponente anlegt oder aendert und das Modell danach selbst speichert. Das Lesen schreibt nie.
 * Frueher entstand er beim ersten Lesen und wurde per updateComponent zurueckgeschrieben; die ersten
 * parallelen User-Anlagen eines frischen Realms schrieben die Komponente so gleichzeitig neu und
 * scheiterten an Keycloaks optimistischer Sperre.
 */
class OrchestratorSettingsTest {

    @Test
    void validateConfigurationAddsTheSigningKeyToTheModelKeycloakPersists() {
        ComponentModel model = component();

        new OrchestratorStorageProviderFactory().validateConfiguration(null, null, model);

        assertNotNull(OrchestratorSettings.from(model).peerAuthSigningKey());
    }

    @Test
    void validateConfigurationKeepsAnExistingKey() {
        ComponentModel model = component();
        var factory = new OrchestratorStorageProviderFactory();
        factory.validateConfiguration(null, null, model);
        String first = model.getConfig().getFirst("peerAuthSigningKeyJwk");

        // Jedes Speichern in der Admin-Console laeuft hier durch - ein neuer Schluessel wuerde jede
        // gerade signierte Assertion entwerten.
        factory.validateConfiguration(null, null, model);

        assertEquals(first, model.getConfig().getFirst("peerAuthSigningKeyJwk"));
    }

    @Test
    void readingAComponentWithoutKeyFailsInsteadOfWriting() {
        ComponentModel model = component();

        assertThrows(IllegalStateException.class, () -> OrchestratorSettings.from(model));
    }

    private static ComponentModel component() {
        ComponentModel model = new ComponentModel();
        model.setName("orchestrator");
        model.getConfig().putSingle(OrchestratorSettings.ORCHESTRATOR_BASE_URL, "http://orchestrator:8080");
        model.getConfig().putSingle(OrchestratorSettings.PUBLIC_ORCHESTRATOR_BASE_URL, "http://localhost:8080");
        model.getConfig().putSingle(OrchestratorSettings.PEER_AUTH_ISSUER, "dpop-demo-keycloak");
        model.getConfig().putSingle(OrchestratorSettings.PEER_AUTH_AUDIENCE, "dpop-demo-orchestrator");
        return model;
    }
}
