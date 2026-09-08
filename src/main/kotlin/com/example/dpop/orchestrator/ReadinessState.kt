package com.example.dpop.orchestrator

import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Ob der Orchestrator bereits echten Traffic bedienen darf. Noetig, weil Tomcat den Port schon
 * oeffnet, BEVOR ApplicationRunner (z.B. KeycloakMigrationRunnerStartup) durchgelaufen sind -
 * Anfragen koennen also waehrend laufender Keycloak-Migrationen eintreffen und mit "invalid_client"
 * scheitern, weil z.B. der orchestrator-admin-Client (V5-Migration) noch gar nicht existiert.
 * [ReadinessGateFilter] blockt genau dieses Fenster.
 */
interface ReadinessState {
    val isReady: Boolean
}

/** Default-Profil (Mock-Keycloak) hat keine Migrationen abzuwarten - immer bereit. */
@Component
@Profile("!keycloak")
class AlwaysReadyState : ReadinessState {
    override val isReady = true
}

/**
 * Startet bewusst als "nicht bereit", noch bevor KeycloakMigrationRunnerStartup ueberhaupt laeuft
 * - der Bean-Konstruktor wird waehrend der Kontext-Bean-Initialisierung aufgerufen, die laut
 * Spring-Boot-Lifecycle VOR dem tatsaechlichen Oeffnen des Server-Ports passiert (finishRefresh()
 * startet den Webserver erst danach). So gibt es kein Zeitfenster, in dem der Port schon offen,
 * aber noch kein Ready-Flag gesetzt ist.
 */
@Component
@Profile("keycloak")
class KeycloakGatedReadinessState : ReadinessState {
    private val ready = AtomicBoolean(false)
    override val isReady: Boolean get() = ready.get()
    fun markReady() = ready.set(true)
}
