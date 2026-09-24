package com.example.dpop.orchestrator.kc

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Das Master-Realm-Token, mit dem [KeycloakMigrationRunnerStartup] das Realm aufbaut - geholt als
 * `orchestrator-migration` per `client_credentials` und signierter Assertion (`private_key_jwt`),
 * nicht mehr als Master-Admin mit Passwort. Den Client legt die Keycloak-Extension beim Start
 * selbst an (`MigrationClientBootstrapFactory`), gegen das JWKS dieses Orchestrators
 * ([OrchestratorClientJwksController]) und mit demselben Schluessel, den auch die Realm-Clients
 * tragen ([OrchestratorClientAssertionSigner]). Damit gibt es zwischen Orchestrator und Keycloak
 * kein geteiltes Geheimnis mehr (ADR-25).
 *
 * Gecacht wie in [KeycloakAdminClient]: Master-Realm-Tokens leben nur 60 Sekunden, der
 * Admin-Client der Migration fragt bei jedem Request hier nach.
 */
@Component
@Profile("keycloak")
class KeycloakMigrationToken(
    // Trust-all-SSLContext muss stehen, bevor der RestClient unten den Default-SSLContext einfriert
    // (siehe KeycloakAdminClient).
    @Suppress("UNUSED_PARAMETER") tlsConfig: KeycloakTlsConfig,
    private val clientAssertions: OrchestratorClientAssertionSigner,
    @Value("\${keycloak-migrate.base-url}") baseUrl: String,
    @Value("\${keycloak-sync.public-base-url}") private val publicBaseUrl: String,
) {
    private val restClient = RestClient.builder().baseUrl(baseUrl).build()

    @Volatile
    private var cached: CachedToken? = null

    fun accessToken(): String {
        cached?.takeIf { Instant.now().isBefore(it.expiresAt) }?.let { return it.value }

        // aud: die OEFFENTLICHE Realm-Adresse - Keycloak bildet die erwartete Audience aus seiner
        // eigenen Frontend-URL, nicht aus dem Weg, ueber den der Aufruf kommt (wie bei den
        // Realm-Clients, KeycloakAdminClient.clientAuth).
        val assertion = clientAssertions.assertionFor(CLIENT_ID, "$publicBaseUrl/realms/$MASTER_REALM")
        val form = "grant_type=client_credentials&client_id=$CLIENT_ID" +
            "&client_assertion_type=${URLEncoder.encode(CLIENT_ASSERTION_TYPE, StandardCharsets.UTF_8)}" +
            "&client_assertion=$assertion"
        val response = restClient.post()
            .uri("/realms/{realm}/protocol/openid-connect/token", MASTER_REALM)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body(form)
            .retrieve()
            .body<Map<String, Any?>>()
            ?: error("Keycloak-Token-Endpunkt (master) lieferte keinen Body")
        val token = response["access_token"] as? String ?: error("Keycloak-Token-Antwort (master) ohne access_token")
        val expiresInSeconds = (response["expires_in"] as? Number)?.toLong() ?: 60L
        // Etwas Luft vor dem echten Ablauf; bei 60 Sekunden Lebensdauer bleibt ein halbes Token.
        val fresh = CachedToken(token, Instant.now().plusSeconds((expiresInSeconds / 2).coerceAtLeast(5)))
        cached = fresh
        return fresh.value
    }

    /**
     * Verwirft das gecachte Token - nach dem Anlegen eines Realms: dessen Admin-Rechte stehen erst
     * in einem danach ausgestellten Token (MigrationRunner.onRealmCreated).
     */
    fun invalidate() {
        cached = null
    }

    private data class CachedToken(val value: String, val expiresAt: Instant)

    companion object {
        /** Muss zu `MigrationClientBootstrapFactory.CLIENT_ID` in der Keycloak-Extension passen. */
        const val CLIENT_ID = "orchestrator-migration"
        private const val MASTER_REALM = "master"
        private const val CLIENT_ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
    }
}
