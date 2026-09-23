package com.example.dpop.orchestrator.kc

import com.example.dpop.kcmigrate.KeycloakSetup
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Leitet die Keycloak-Laufzeitwerte des Orchestrators aus dem gewaehlten Migrations-Parametersatz
 * ab, statt sie ein zweites Mal von Hand zu konfigurieren.
 *
 * Realm-Name, Keycloak-Adresse und Client-Ids sind fuer Aufbau und Betrieb derselbe Wert: der
 * Sync spricht genau das Realm an, das die Migration anlegt, als genau der Client, den sie
 * angelegt hat (angemeldet per private_key_jwt, ADR-25). Standen sie an zwei Orten, war "Migration gegen
 * Realm A, Sync gegen Realm B" ein Tippfehler weit - und faellt erst als 401 irgendwo tief im
 * Betrieb auf. Hier gibt es sie einmal, alles andere ist abgeleitet.
 *
 * Als [EnvironmentPostProcessor] und nicht als Bean, weil `@Value`-Platzhalter wie
 * `${kc.oidc.issuer}` schon beim Erzeugen der Beans aufgeloest werden - die Werte muessen also in
 * der Environment stehen, bevor irgendeine Bean entsteht. Die betroffenen Klassen
 * ([KeycloakAdminClient], [PeerAuthValidator], [KeycloakJwkSource], [KeycloakOidcTokenValidator])
 * bleiben dadurch unveraendert bei ihren Properties; nur deren Herkunft aendert sich.
 */
class KeycloakSetupEnvironment : EnvironmentPostProcessor, Ordered {

    /**
     * Nach [org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor]
     * (HIGHEST_PRECEDENCE + 10): erst danach sind application-keycloak.yml geladen und die
     * aktiven Profile bekannt.
     */
    override fun getOrder() = Ordered.HIGHEST_PRECEDENCE + 20

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (PROFILE !in environment.activeProfiles) return
        val setup = resolve(environment)
        val realms = "${setup.access.keycloakBaseUrl}/realms/${setup.realm.realmName}"
        environment.propertySources.addFirst(
            MapPropertySource(
                "migration-setup-derived",
                mapOf(
                    // Wohin die Migration selbst schreibt - und mit welchem Zugang.
                    "keycloak-migrate.base-url" to setup.access.keycloakBaseUrl,
                    "keycloak-migrate.admin-username" to setup.access.adminUsername,
                    "keycloak-migrate.admin-password" to setup.access.adminPassword,
                    // Account-Sync: dasselbe Realm, dieselben Clients wie die Migration sie anlegt.
                    "keycloak-sync.base-url" to setup.access.keycloakBaseUrl,
                    "keycloak-sync.realm" to setup.realm.realmName,
                    "keycloak-sync.public-base-url" to setup.access.publicKeycloakBaseUrl,
                    "keycloak-sync.admin-client-id" to setup.realm.adminApiClientId,
                    "keycloak-sync.app-client-id" to setup.realm.appTokenClientId,
                    // Peer-Auth: iss/aud muessen exakt das sein, was die Extension signiert -
                    // und das steht als Config-Property an der orchestrator-Komponente im Realm,
                    // gesetzt aus genau diesen Feldern.
                    "kc.peer-auth.issuer" to setup.realm.peerAuthIssuer,
                    "kc.peer-auth.audience" to setup.realm.peerAuthAudience,
                    "kc.peer-auth.jwks-uri" to "$realms/orchestrator-jwks/.well-known/jwks.json",
                    // Der Browser bekommt sein Token von Keycloaks oeffentlichem Port, der
                    // Issuer im Token traegt also die oeffentliche Adresse - die Schluessel holt
                    // der Orchestrator trotzdem ueber den internen Weg.
                    "kc.oidc.issuer" to "${setup.access.publicKeycloakBaseUrl}/realms/${setup.realm.realmName}",
                    "kc.oidc.certs-uri" to "$realms/protocol/openid-connect/certs",
                ),
            ),
        )
    }

    /**
     * Bindet dieselbe Klasse, die spaeter als Bean die Migration steuert - damit gibt es die
     * Auflösung (Basis, dann die fuer alle geltenden Werte, dann die Variante) nur einmal, und der
     * Startup-Hook kann gar nicht mit einem anderen Satz laufen als dem, aus dem diese Properties
     * stammen.
     */
    private fun resolve(environment: ConfigurableEnvironment): KeycloakSetup =
        Binder.get(environment)
            .bind(ConfiguredKeycloakSetupSource.PREFIX, Bindable.of(ConfiguredKeycloakSetupSource::class.java))
            .orElseGet { ConfiguredKeycloakSetupSource() }
            .selected()

    private companion object {
        const val PROFILE = "keycloak"
    }
}
