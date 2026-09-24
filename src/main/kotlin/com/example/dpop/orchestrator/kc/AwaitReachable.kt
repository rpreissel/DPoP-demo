package com.example.dpop.orchestrator.kc

import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Wartet, bis [probe] ohne Exception durchlaeuft - fuer den Start des Orchestrators, bevor er
 * Keycloak migriert ([KeycloakMigrationRunnerStartup]).
 *
 * Unter Compose sorgte `depends_on: service_healthy` fuer die Reihenfolge. In Kubernetes gibt es das
 * nicht, und laufen beide Container im selben Pod, starten Init-Container vor BEIDEN - keiner von
 * ihnen kann auf Keycloak warten. Ohne dieses Warten scheiterte der Start, und die Plattform startete
 * den Container mit wachsenden Pausen neu (CrashLoopBackOff, bis zu fuenf Minuten). Also wartet die
 * Anwendung selbst auf ihre Abhaengigkeit - das in Kubernetes uebliche Muster.
 *
 * Nach [timeout] gibt sie auf und wirft die letzte Exception weiter: dann stimmt eher die Adresse
 * nicht, als dass Keycloak noch hochfaehrt, und ein lauter Fehler ist besser als endloses Warten.
 */
class AwaitReachable(
    private val timeout: Duration,
    private val interval: Duration = Duration.ofSeconds(2),
    private val now: () -> Instant = Instant::now,
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
    private val log = LoggerFactory.getLogger(AwaitReachable::class.java)

    fun await(what: String, probe: () -> Unit) {
        val deadline = now().plus(timeout)
        var attempts = 0
        while (true) {
            try {
                probe()
                if (attempts > 0) log.info("{} ist erreichbar (nach {} Versuchen)", what, attempts + 1)
                return
            } catch (e: Exception) {
                attempts++
                if (!now().isBefore(deadline)) {
                    throw IllegalStateException("$what nach $timeout nicht erreichbar", e)
                }
                // Nicht jeden Versuch loggen - beim normalen Start sind es ein paar, das Log bleibt lesbar.
                if (attempts == 1 || attempts % 10 == 0) log.info("Warte auf {}: {}", what, e.message)
                sleep(interval)
            }
        }
    }
}
