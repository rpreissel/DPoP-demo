package com.example.dpop.orchestrator.kc

import com.example.dpop.kcmigrate.MigrationFile
import com.example.dpop.kcmigrate.MigrationRunner
import com.example.dpop.kcmigrate.MigrationStepFailedException
import com.example.dpop.kcmigrate.buildAdminClient
import com.example.dpop.orchestrator.KeycloakGatedReadinessState
import org.slf4j.LoggerFactory
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Wendet alle .kc.kts-Migrationen aus dem keycloak-migrations-Jar beim Start des Orchestrators an - Ersatz für den
 * separaten infra/tofu/keycloak-Schritt (docs zu MigrationRunner). Läuft als ApplicationRunner,
 * nicht als @PostConstruct - fügt sich damit ins gleiche Bean-Lifecycle-Muster wie Flyways eigener
 * Migrations-Hook.
 *
 * ACHTUNG: ApplicationRunner laufen NICHT vor dem ersten bedienten Request - Tomcat öffnet den
 * Port bereits während finishRefresh(), also bevor SpringApplication.run() die Runner aufruft.
 * Ohne Gegenmaßnahme können echte Requests also mitten in diese Migrationen hineinlaufen und z.B.
 * mit "invalid_client" scheitern, weil der orchestrator-admin-Client (V5) noch nicht existiert -
 * [KeycloakGatedReadinessState]/[ReadinessGateFilter] blocken dieses Fenster mit einem klaren 503
 * statt eines verwirrenden 500ers.
 *
 * Meldet sich als `orchestrator-migration` im Master-Realm an ([KeycloakMigrationToken], signierte
 * Assertion, kein Passwort), nicht als keycloak-sync's orchestrator-admin-Service-Account
 * (KeycloakAdminClient) - der wird von V5 erst angelegt, existiert beim allerersten Lauf also noch
 * nicht.
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
    private val readinessState: KeycloakGatedReadinessState,
    private val paramsSource: ConfiguredKeycloakSetupSource,
    private val migrationToken: KeycloakMigrationToken,
    @Value("\${keycloak-migrate.base-url}") private val baseUrl: String,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(KeycloakMigrationRunnerStartup::class.java)

    override fun run(args: ApplicationArguments) {
        val setup = paramsSource.selected()
        val migrations = loadMigrations()
        log.info(
            "Keycloak-Migrationen: wende {} auf Realm '{}' an (Variante '{}')",
            migrations.map { it.name }, setup.realm.realmName, paramsSource.variant,
        )
        val kc = buildAdminClient(baseUrl, migrationToken::accessToken, insecure = true)
        val runner = MigrationRunner(kc, setup.realm, migrations, onRealmCreated = migrationToken::invalidate)
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
        readinessState.markReady()
    }

    /**
     * Die Migrationen kommen als Ressourcen aus dem keycloak-migrations-Jar, nicht aus einem
     * Verzeichnis daneben: sie gehoeren zu genau dem Artefakt, dessen Code sie voraussetzen.
     * `classpath*:` statt `classpath:`, damit es beim Suchen bleibt, falls sie einmal aus mehr als
     * einem Jar kommen.
     */
    private fun loadMigrations(): List<MigrationFile> {
        val resources = PathMatchingResourcePatternResolver().getResources(MIGRATIONS_PATTERN)
        val migrations = resources.mapNotNull { resource ->
            val name = resource.filename ?: return@mapNotNull null
            MigrationFile.parse(name, resource.inputStream.use { it.reader().readText() })
        }
        // Ein leerer Lauf waere kein harmloser No-Op, sondern ein Realm ohne Flows und Clients -
        // besser hier abbrechen als spaeter an einem fehlenden Client.
        if (migrations.isEmpty()) error("Keine Migrationen unter $MIGRATIONS_PATTERN gefunden")
        return migrations
    }

    private companion object {
        const val MIGRATIONS_PATTERN = "classpath*:keycloak-migrations/*.kc.kts"
    }
}
