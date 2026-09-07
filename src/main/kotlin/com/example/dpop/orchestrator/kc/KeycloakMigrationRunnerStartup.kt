package com.example.dpop.orchestrator.kc

import com.example.dpop.kcmigrate.MigrationRunner
import com.example.dpop.kcmigrate.MigrationStepFailedException
import com.example.dpop.kcmigrate.buildAdminClient
import java.nio.file.Path
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Wendet alle .kc.kts-Dateien aus keycloak-migrations/migrations beim Start des Orchestrators an - Ersatz für den
 * separaten infra/tofu/keycloak-Schritt (docs zu MigrationRunner). Läuft als ApplicationRunner
 * (nach Kontext-Start, vor dem ersten bedienten Request), nicht als @PostConstruct - fügt sich
 * damit ins gleiche Bean-Lifecycle-Muster wie Flyways eigener Migrations-Hook.
 *
 * Braucht echte Master-Realm-Admin-Credentials (keycloak-migrate.admin-*), nicht keycloak-sync's
 * orchestrator-admin-Service-Account (KeycloakAdminClient) - der wird von V5 erst angelegt,
 * existiert beim allerersten Lauf also noch nicht.
 *
 * @Order(HIGHEST_PRECEDENCE): muss vor JEDEM anderen ApplicationRunner laufen, der Keycloak
 * anspricht (z.B. KcDemoAccountSeeder) - sonst schlägt dessen allererster Zugriff mit
 * "Realm does not exist" fehl, weil das Realm zu dem Zeitpunkt noch nicht existiert.
 */
@Component
@Profile("keycloak")
@Order(Ordered.HIGHEST_PRECEDENCE)
class KeycloakMigrationRunnerStartup(
    // Erzwingt Spring, den Trust-all-SSLContext (siehe KeycloakAdminClient's gleiches Muster)
    // VOR diesem Runner zu installieren.
    @Suppress("UNUSED_PARAMETER") tlsConfig: KeycloakTlsConfig,
    @Value("\${keycloak-migrate.base-url}") private val baseUrl: String,
    @Value("\${keycloak-migrate.realm}") private val realm: String,
    @Value("\${keycloak-migrate.admin-username}") private val adminUsername: String,
    @Value("\${keycloak-migrate.admin-password}") private val adminPassword: String,
    @Value("\${keycloak-migrate.migrations-dir}") private val migrationsDir: String,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(KeycloakMigrationRunnerStartup::class.java)

    override fun run(args: ApplicationArguments) {
        log.info("Keycloak-Migrationen: wende {} auf Realm '{}' an", migrationsDir, realm)
        val kc = buildAdminClient(baseUrl, adminUsername, adminPassword, insecure = true)
        val runner = MigrationRunner(kc, realm, Path.of(migrationsDir))
        try {
            runner.up()
        } catch (e: MigrationStepFailedException) {
            log.error("Keycloak-Migration {} fehlgeschlagen - rolle sie zurück und breche den Start ab", e.fileName, e)
            runCatching { runner.down(e.fileVersion) }
                .onFailure { rollbackError ->
                    log.error("Rollback von Migration {} zusätzlich fehlgeschlagen", e.fileName, rollbackError)
                }
            throw e
        } finally {
            kc.close()
        }
        log.info("Keycloak-Migrationen abgeschlossen.")
    }
}
