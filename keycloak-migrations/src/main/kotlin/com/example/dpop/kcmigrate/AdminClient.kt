package com.example.dpop.kcmigrate

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.ws.rs.ext.ContextResolver
import jakarta.ws.rs.ext.Provider
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.KeycloakBuilder

/**
 * keycloak-admin-client 26.0.12 (letzte auf Maven Central verfügbare Version, siehe
 * build.gradle.kts) bündelt ein eigenes, unabhängig weiterentwickeltes Representation-Modell
 * (keycloak-client-common-synced) - das enthält u.a. schon "maxSecondaryAuthFailures", ein Feld,
 * das der 26.5.5-Server (noch) nicht kennt. Beim Zurückschreiben von realm.toRepresentation()
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
 * insecure=true entspricht tls_insecure_skip_verify in infra/tofu/keycloak/main.tf - der
 * Compose-Stack spricht Keycloak über ein selbstsigniertes Zertifikat an.
 */
fun buildAdminClient(url: String, username: String, password: String, insecure: Boolean): Keycloak {
    val clientBuilder = ResteasyClientBuilder.newBuilder() as ResteasyClientBuilder
    if (insecure) clientBuilder.disableTrustManager()
    val resteasyClient = clientBuilder.register(LenientJacksonResolver()).build()

    return KeycloakBuilder.builder()
        .serverUrl(url)
        .realm("master")
        .clientId("admin-cli")
        .username(username)
        .password(password)
        .resteasyClient(resteasyClient)
        .build()
}
