package com.example.dpop.orchestrator.kc

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.health.contributor.AbstractHealthIndicator
import org.springframework.boot.health.contributor.Health
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * `keycloak` in `/actuator/health` (docs/07-betrieb.md Abschnitt 7): whether Keycloak answers its
 * public realm endpoint - the same probe the startup waits on (`KeycloakMigrationRunnerStartup`).
 *
 * Deliberately not part of the readiness group: the App channel works without Keycloak, and a
 * Keycloak outage taking every orchestrator instance out of the load balancer would turn a partial
 * outage into a full one. It is for alerting, not for routing.
 */
@Component("keycloak")
@Profile("keycloak")
class KeycloakHealthIndicator(
    keycloakHttp: KeycloakHttp,
    @Value("\${keycloak-sync.base-url}") baseUrl: String,
    @Value("\${keycloak-sync.realm}") private val realm: String,
) : AbstractHealthIndicator("Keycloak is not reachable") {
    private val restClient = keycloakHttp.restClient(baseUrl)

    override fun doHealthCheck(builder: Health.Builder) {
        restClient.get().uri("/realms/{realm}", realm).retrieve().toBodilessEntity()
        builder.up()
    }
}
