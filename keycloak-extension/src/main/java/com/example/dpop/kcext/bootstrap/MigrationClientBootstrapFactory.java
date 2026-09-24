package com.example.dpop.kcext.bootstrap;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.AdminRoles;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.models.utils.PostMigrationEvent;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.services.managers.ClientManager;
import org.keycloak.services.managers.RealmManager;

/**
 * Legt im Master-Realm den Client an, mit dem der Orchestrator seine Keycloak-Migrationen
 * ausfuehrt - {@code orchestrator-migration}, angemeldet per {@code private_key_jwt} (RFC 7523)
 * gegen das JWKS des Orchestrators. Damit braucht der Orchestrator keinen Master-Admin mit
 * Passwort mehr: das letzte geteilte Geheimnis zwischen beiden Seiten (ADR-25) entfaellt.
 *
 * <p>Laeuft in der Extension selbst, beim Start von Keycloak nach dessen eigener Datenbank-Migration
 * ({@link PostMigrationEvent}) - ohne Admin-Login, ohne Skript, das auf einen fertigen Server warten
 * muesste. Idempotent: fehlt der Client, entsteht er; gibt es ihn, wird nur die {@code jwks.url}
 * auf den konfigurierten Stand gebracht.
 *
 * <p>Rechte: nur {@code create-realm}. Keycloak macht den Anleger eines Realms automatisch zu dessen
 * Admin - das deckt Aufbau und Neuaufbau (Loeschen und neu Anlegen) ab, ohne Master-Admin zu sein.
 * Ein Realm, das jemand anderes angelegt hat (etwa der Bootstrap-Admin vor dieser Umstellung),
 * bleibt fuer diesen Client unerreichbar; der Orchestrator meldet das beim Start mit klarer Ursache.
 *
 * <p>Die {@code jwks.url} ist der eine Wert dieser Extension, der NICHT im Realm steht (ADR-25):
 * es gibt beim Start noch kein Realm, aus dem er kommen koennte. Er kommt deshalb aus der
 * SPI-Konfiguration des Keycloak-Containers ({@code KC_SPI_ORCHESTRATOR_BOOTSTRAP__MIGRATION_CLIENT__JWKS_URL})
 * und zeigt auf dieselbe Orchestrator-Adresse wie {@code orchestratorBaseUrl} der Setup-Variante.
 * Stimmt er nicht, scheitert der erste Token-Request der Migration sofort - kein spaeter Fehler.
 * Die URL muss auf dem internen Netz bleiben: wer unter ihr antwortet, kann sich Tokens dieses
 * Clients ausstellen.
 */
public class MigrationClientBootstrapFactory implements OrchestratorBootstrapFactory {

    public static final String ID = "migration-client";
    public static final String CLIENT_ID = "orchestrator-migration";
    static final String JWKS_URL = "jwks-url";

    private static final Logger log = Logger.getLogger(MigrationClientBootstrapFactory.class);

    private String jwksUrl;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public OrchestratorBootstrap create(KeycloakSession session) {
        return () -> {
        };
    }

    @Override
    public void init(Config.Scope config) {
        jwksUrl = config.get(JWKS_URL);
        // Bewusst ohne Fallback: ohne diesen Wert kann sich die Migration nicht anmelden. Besser
        // Keycloak startet gar nicht, als dass der Fehler erst beim Orchestrator als invalid_client
        // auftaucht.
        if (jwksUrl == null || jwksUrl.isBlank()) {
            throw new IllegalStateException("SPI-Option spi-orchestrator-bootstrap--" + ID + "--" + JWKS_URL
                    + " fehlt - ohne sie kann sich die Migration des Orchestrators nicht anmelden");
        }
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        factory.register(event -> {
            if (event instanceof PostMigrationEvent) {
                KeycloakModelUtils.runJobInTransaction(factory, this::ensureClient);
            }
        });
    }

    void ensureClient(KeycloakSession session) {
        RealmModel master = session.realms().getRealmByName(Config.getAdminRealm());
        // Die Job-Session hat keinen Realm-Kontext; ClientManager.enableServiceAccount (und was es
        // intern anlegt) scheitert ohne ihn mit "Session not bound to a realm" - beim Start, also
        // bevor Keycloak ueberhaupt laeuft.
        session.getContext().setRealm(master);
        ClientModel client = master.getClientByClientId(CLIENT_ID);
        if (client == null) {
            client = master.addClient(CLIENT_ID);
            client.setName(CLIENT_ID);
            client.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);
            client.setPublicClient(false);
            client.setStandardFlowEnabled(false);
            client.setImplicitFlowEnabled(false);
            client.setDirectAccessGrantsEnabled(false);
            client.setClientAuthenticatorType("client-jwt");
            // Dieselben Attribute wie die Realm-Clients (V1__realm.kc.kts,
            // orchestratorClientJwtAttributes): der Orchestrator signiert mit EC P-256.
            client.setAttribute("use.jwks.url", "true");
            client.setAttribute("token.endpoint.auth.signing.alg", "ES256");
            new ClientManager(new RealmManager(session)).enableServiceAccount(client);
            UserModel serviceAccount = session.users().getServiceAccount(client);
            RoleModel createRealm = master.getRole(AdminRoles.CREATE_REALM);
            serviceAccount.grantRole(createRealm);
            log.infof("Client '%s' im Master-Realm angelegt (create-realm, private_key_jwt)", CLIENT_ID);
        }
        if (!jwksUrl.equals(client.getAttribute("jwks.url"))) {
            client.setAttribute("jwks.url", jwksUrl);
            log.infof("Client '%s': jwks.url = %s", CLIENT_ID, jwksUrl);
        }
    }

    @Override
    public void close() {
    }
}
