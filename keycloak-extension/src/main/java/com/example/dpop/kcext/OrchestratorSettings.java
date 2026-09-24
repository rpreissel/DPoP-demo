package com.example.dpop.kcext;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.provider.ProviderConfigProperty;

import java.text.ParseException;
import java.util.List;

/**
 * Die Konfiguration dieser Extension - abgelegt als Config-Properties der
 * {@code orchestrator}-User-Storage-Komponente des Realms, nicht als Umgebungsvariablen des
 * Containers. Damit steht sie dort, wo jede andere Realm-Einstellung dieses Projekts auch steht:
 * sichtbar in der Admin-Console, Teil des Realm-Exports, gesetzt von der Migration
 * (keycloak-migrations/src/main/resources/keycloak-migrations/V1__realm.kc.kts).
 *
 * <p>Bewusst ohne Fallback: fehlt die Komponente oder ein Wert, scheitert der Aufruf laut. Ein
 * stiller Default waere hier genau das, was die frueher gelesenen Env-Vars so unangenehm gemacht
 * hat - eine Fehlkonfiguration, die erst am fremden Verhalten eines Logins auffaellt.
 *
 * @param orchestratorBaseUrl Server-zu-Server: wie der Keycloak-Container den Orchestrator
 *        erreicht (kc-facade, docs/05-api.md Abschnitt 3).
 * @param publicOrchestratorBaseUrl Dieselbe Anwendung, andere Blickrichtung: der
 *        browser-aufloesbare Origin. Nur fuer den Peer-Login-Deep-Link im QR-Code
 *        (docs/07-betrieb.md #5) - der wird auf einem fremden Geraet geoeffnet, dort traegt
 *        {@code orchestratorBaseUrl} nicht.
 * @param peerAuthIssuer {@code iss} der Peer-Auth-Assertion (docs/12-entscheidungen.md ADR-7).
 * @param peerAuthAudience {@code aud} derselben Assertion.
 * @param peerAuthSigningKey Der Schluessel, mit dem diese Assertion signiert wird - liegt
 *        ebenfalls an der Komponente und damit in Keycloaks eigener Datenbank, statt wie frueher
 *        je JVM-Lauf neu zu entstehen. Mehrere Keycloak-Knoten signieren so mit demselben
 *        Schluessel, und ein Neustart entwertet keine laufende Sitzung.
 */
public record OrchestratorSettings(
        String orchestratorBaseUrl,
        String publicOrchestratorBaseUrl,
        String peerAuthIssuer,
        String peerAuthAudience,
        ECKey peerAuthSigningKey
) {

    public static final String ORCHESTRATOR_BASE_URL = "orchestratorBaseUrl";
    public static final String PUBLIC_ORCHESTRATOR_BASE_URL = "publicOrchestratorBaseUrl";
    public static final String PEER_AUTH_ISSUER = "peerAuthIssuer";
    public static final String PEER_AUTH_AUDIENCE = "peerAuthAudience";

    /** Interner Zustand, kein Eingabefeld - siehe {@link #signingKey}. */
    private static final String PEER_AUTH_SIGNING_KEY = "peerAuthSigningKeyJwk";

    /**
     * Was die Admin-Console an der Komponente anzeigt - und zugleich die Liste der Schluessel, die
     * {@link #from(ComponentModel)} erwartet. Ein Ort, nicht zwei.
     */
    public static final List<ProviderConfigProperty> CONFIG_PROPERTIES = List.of(
            property(ORCHESTRATOR_BASE_URL, "Orchestrator base URL",
                    "Server-zu-Server: wie dieser Keycloak den Orchestrator erreicht, z.B. http://orchestrator:8080"),
            property(PUBLIC_ORCHESTRATOR_BASE_URL, "Public orchestrator base URL",
                    "Derselbe Orchestrator, wie ein Browser ihn erreicht - Basis des QR-Deep-Links, z.B. http://localhost:8080"),
            property(PEER_AUTH_ISSUER, "Peer-auth issuer",
                    "iss-Claim der signierten Peer-Auth-Assertion an den Orchestrator"),
            property(PEER_AUTH_AUDIENCE, "Peer-auth audience",
                    "aud-Claim derselben Assertion")
    );

    private static ProviderConfigProperty property(String name, String label, String helpText) {
        ProviderConfigProperty property = new ProviderConfigProperty();
        property.setName(name);
        property.setLabel(label);
        property.setHelpText(helpText);
        property.setType(ProviderConfigProperty.STRING_TYPE);
        return property;
    }

    /** Fuer die Komponente selbst, die ihr eigenes {@link ComponentModel} schon in der Hand hat. */
    public static OrchestratorSettings from(ComponentModel model) {
        return new OrchestratorSettings(
                required(model, ORCHESTRATOR_BASE_URL),
                required(model, PUBLIC_ORCHESTRATOR_BASE_URL),
                required(model, PEER_AUTH_ISSUER),
                required(model, PEER_AUTH_AUDIENCE),
                signingKey(model)
        );
    }

    /**
     * Legt den Signaturschluessel an, falls die Komponente noch keinen hat - aufgerufen aus
     * {@link OrchestratorStorageProviderFactory#validateConfiguration}, also genau dann, wenn
     * Keycloak die Komponente anlegt oder aendert. Keycloak speichert das so ergaenzte Modell
     * direkt mit; dasselbe Verfahren wie bei seinen eigenen {@code *-generated}-Key-Providern.
     *
     * Bewusst KEIN Eintrag in {@link #CONFIG_PROPERTIES}: das ist interner Zustand, kein Feld, das
     * jemand in der Admin-Console ausfuellen soll. Es liegt damit in Keycloaks Datenbank wie jeder
     * andere Realm-Schluessel auch.
     *
     * Frueher entstand der Schluessel erst beim ersten Lesen und wurde dann per
     * {@code realm.updateComponent} zurueckgeschrieben. Keycloak instanziiert den Storage-Provider
     * aber bei jeder User-Anlage - die ersten parallelen Anlagen eines frischen Realms schrieben
     * also alle gleichzeitig die Komponente neu, und alle ausser einer scheiterten an Keycloaks
     * optimistischer Sperre auf COMPONENT_CONFIG. Nicht erst bei mehreren Knoten, sondern schon
     * bei drei gleichzeitigen Account-Syncs eines einzigen Orchestrators.
     */
    static void ensureSigningKey(ComponentModel model) {
        String stored = model.getConfig().getFirst(PEER_AUTH_SIGNING_KEY);
        if (stored == null || stored.isBlank()) {
            model.getConfig().putSingle(PEER_AUTH_SIGNING_KEY, generateSigningKey().toJSONString());
        }
    }

    /** Liest nur - angelegt wird der Schluessel ausschliesslich in {@link #ensureSigningKey}. */
    private static ECKey signingKey(ComponentModel model) {
        String stored = model.getConfig().getFirst(PEER_AUTH_SIGNING_KEY);
        if (stored == null || stored.isBlank()) {
            throw new IllegalStateException("Komponente '" + model.getName()
                    + "' hat keinen Peer-Auth-Signaturschluessel - einmal in der Admin-Console speichern,"
                    + " dann legt validateConfiguration ihn an");
        }
        try {
            return ECKey.parse(stored);
        } catch (ParseException e) {
            throw new IllegalStateException("Peer-Auth-Signaturschluessel der Komponente ist unlesbar", e);
        }
    }

    private static ECKey generateSigningKey() {
        try {
            return new ECKeyGenerator(Curve.P_256)
                    .keyID("kc-ext-" + System.currentTimeMillis())
                    .algorithm(JWSAlgorithm.ES256)
                    .keyUse(KeyUse.SIGNATURE)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to generate peer-auth signing key", e);
        }
    }

    /**
     * Fuer alle uebrigen Bausteine (Authenticators, Required Action, Renderer): sie haben eine
     * Session, aber kein eigenes Komponentenmodell, und finden die Komponente ueber ihre
     * providerId - die eigene Komponenten-Id variiert pro Umgebung, genauso wie
     * {@code KeycloakAdminClient} es auf der Orchestrator-Seite schon haelt.
     */
    public static OrchestratorSettings of(KeycloakSession session) {
        RealmModel realm = session.getContext().getRealm();
        return from(OrchestratorStorageProviderFactory.componentIn(realm)
                .orElseThrow(() -> new IllegalStateException(
                        "User-Storage-Komponente '" + OrchestratorStorageProviderFactory.PROVIDER_ID
                                + "' fehlt im Realm '" + realm.getName()
                                + "' - ohne sie hat diese Extension keine Konfiguration")));
    }

    /** Ein Client mit genau diesen Einstellungen - spart den gleichlautenden Dreiklang an fuenf Stellen. */
    OrchestratorClient newClient() {
        return new OrchestratorClient(orchestratorBaseUrl, peerAuthIssuer, peerAuthAudience, peerAuthSigningKey);
    }

    private static String required(ComponentModel model, String key) {
        String value = model.getConfig().getFirst(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Config-Property '" + key + "' der Komponente '" + model.getName() + "' ist nicht gesetzt");
        }
        return value;
    }
}
