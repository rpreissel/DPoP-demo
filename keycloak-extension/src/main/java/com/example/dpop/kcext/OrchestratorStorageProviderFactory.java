package com.example.dpop.kcext;

import org.keycloak.Config;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.UserStorageProviderFactory;

import java.util.List;
import java.util.Optional;

/**
 * Factory fuer {@link OrchestratorStorageProvider} - und zugleich die Komponente, an der die
 * Konfiguration dieser ganzen Extension haengt ({@link OrchestratorSettings}): sie ist die einzige
 * Realm-Komponente, die das Plugin mitbringt, also der natuerliche Ort dafuer. Jeder andere
 * Baustein findet sie ueber {@link #PROVIDER_ID}.
 *
 * <p>Der {@link OrchestratorClient} entsteht pro Komponenteninstanz aus deren eigener Config, nicht
 * mehr einmal pro JVM in {@link #init(Config.Scope)}: nur so kommt eine in der Admin-Console
 * geaenderte URL ueberhaupt an, ohne Keycloak neu zu starten.
 */
public class OrchestratorStorageProviderFactory implements UserStorageProviderFactory<OrchestratorStorageProvider> {

    public static final String PROVIDER_ID = "orchestrator";

    /**
     * Die eine Komponente dieses Realms - gesucht ueber die providerId, weil ihre eigene Id pro
     * Umgebung variiert (dieselbe Begruendung, aus der {@code KeycloakAdminClient} sie auf der
     * Orchestrator-Seite ebenfalls so sucht). Leer, solange die Migration sie nicht angelegt hat;
     * die beiden Aufrufer gehen damit unterschiedlich um: {@link OrchestratorSettings#of} scheitert,
     * das Setzen des federationLink in {@link OrchestratorAuthenticator} laesst es dann bleiben.
     */
    static Optional<ComponentModel> componentIn(RealmModel realm) {
        return realm.getStorageProviders(UserStorageProvider.class)
                .filter(component -> PROVIDER_ID.equals(component.getProviderId()))
                .findFirst();
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getHelpText() {
        return "Traegt die Konfiguration der Orchestrator-Extension und delegiert Passwort-Pruefung/-Aenderung "
                + "an den eigenen auth_password-Speicher des Orchestrators - kein Passwort liegt je in Keycloak, "
                + "gleiches Prinzip wie ein LDAP-Federation-Provider, der an sein Verzeichnis delegiert.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return OrchestratorSettings.CONFIG_PROPERTIES;
    }

    @Override
    public OrchestratorStorageProvider create(KeycloakSession session, ComponentModel model) {
        return new OrchestratorStorageProvider(OrchestratorSettings.from(session, model).newClient());
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
