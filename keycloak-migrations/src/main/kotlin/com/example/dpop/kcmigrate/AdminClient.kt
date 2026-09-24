package com.example.dpop.kcmigrate

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.ext.ContextResolver
import jakarta.ws.rs.ext.Provider
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.KeycloakBuilder

/**
 * keycloak-admin-client 26.0.12 (letzte auf Maven Central verfügbare Version, siehe
 * build.gradle.kts) bündelt ein eigenes, unabhängig weiterentwickeltes Representation-Modell
 * (keycloak-client-common-synced) - das enthält u.a. schon "maxSecondaryAuthFailures", ein Feld,
 * das der 26.6.4-Server (noch) nicht kennt. Beim Zurückschreiben von realm.toRepresentation()
 * landet dieses Feld sonst als "maxSecondaryAuthFailures": null im PUT-Body, und der Server lehnt
 * es mit 400 "Unrecognized field" ab. NON_NULL unterdrückt genau solche nicht gesetzten,
 * client-seitig zusätzlichen Felder in der Ausgabe; FAIL_ON_UNKNOWN_PROPERTIES=false macht das
 * Gleiche für die Gegenrichtung robust, falls der Server mal ein Feld mehr kennt als der Client.
 */
@Provider
class LenientJacksonResolver : ContextResolver<ObjectMapper> {
    private val mapper = ObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
    }

    override fun getContext(type: Class<*>?): ObjectMapper = mapper
}

/**
 * Setzt bei JEDEM Request ein frisches Bearer-Token aus [accessToken] - statt des einen, festen
 * Tokens, das der Admin-Client per `authorization(...)` sonst unveraendert bis zum Ende verwendet.
 *
 * Noetig, weil keycloak-admin-client 26.0.12 sich selbst nur per Passwort oder `client_secret`
 * anmelden kann, nicht per signierter Assertion (`private_key_jwt`). Das Token holt deshalb der
 * Aufrufer; Master-Realm-Tokens leben nur 60 Sekunden, ein fester Wert liefe mitten in der
 * Migration ab. Die Prioritaet liegt hinter der des admin-client-eigenen BearerAuthFilter
 * (Priorities.USER), Request-Filter laufen aufsteigend - dieser hier hat also das letzte Wort.
 */
@Priority(Priorities.USER + 100)
private class FreshBearerToken(private val accessToken: () -> String) : ClientRequestFilter {
    override fun filter(requestContext: ClientRequestContext) {
        requestContext.headers.putSingle(HttpHeaders.AUTHORIZATION, "Bearer ${accessToken()}")
    }
}

/**
 * [accessToken] liefert ein gueltiges Master-Realm-Token (der Aufrufer cacht und erneuert es).
 * insecure=true entspricht tls_insecure_skip_verify in infra/tofu/keycloak/main.tf - der
 * Compose-Stack spricht Keycloak über ein selbstsigniertes Zertifikat an.
 */
fun buildAdminClient(url: String, accessToken: () -> String, insecure: Boolean): Keycloak {
    val clientBuilder = ResteasyClientBuilder.newBuilder() as ResteasyClientBuilder
    if (insecure) clientBuilder.disableTrustManager()
    val resteasyClient = clientBuilder
        .register(LenientJacksonResolver())
        .register(FreshBearerToken(accessToken))
        .build()

    return KeycloakBuilder.builder()
        .serverUrl(url)
        .realm("master")
        // Platzhalter: ein gesetztes authorization haelt den Admin-Client davon ab, sich selbst
        // anmelden zu wollen. Das tatsaechliche Token setzt FreshBearerToken.
        .authorization(TOKEN_PLACEHOLDER)
        .resteasyClient(resteasyClient)
        .build()
}

private const val TOKEN_PLACEHOLDER = "set-per-request"
