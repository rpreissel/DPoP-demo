package com.example.dpop.orchestrator.schema

import org.slf4j.LoggerFactory
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * Lets every module keep its own migrations in its own folder under `db/migration/`.
 *
 * Each module owns a database schema (`auth_sms.*`, `account.*`, ..., ADR-16) and the migrations
 * that build it, in `db/migration/<module>/` (ADR-16). Before that, one shared file created the
 * tables of every module, so adding a method module meant editing a file shared with all others -
 * the one place module autonomy still broke.
 *
 * Discovered rather than listed: a hard-coded `spring.flyway.locations` would just move the shared
 * file one level up - a central list a new module can be forgotten from, which is exactly the
 * failure mode the tool catalog and the retention sweep were already built to avoid. A module gets
 * its migrations run by creating the folder, and by nothing else. `classpath:db/migration` itself
 * holds no SQL any more, only `KONVENTIONEN.md`.
 *
 * Versions remain ONE sequence across all folders - Flyway orders by version, not by location. Two
 * modules picking the same version is a startup failure ("Found more than one migration with
 * version X"), loud and immediate, which is the right outcome: it means two migrations disagree
 * about what runs first.
 */
@Configuration
class ModuleMigrationLocations(@Value("\${demo.mode:true}") private val demoMode: Boolean) {

    @Bean
    fun perModuleMigrationLocations(): FlywayConfigurationCustomizer = FlywayConfigurationCustomizer { configuration ->
        val discovered = moduleLocations()
        if (discovered.isEmpty()) return@FlywayConfigurationCustomizer
        configuration.locations(*(configuration.locations.map { it.descriptor } + discovered).toTypedArray())
        log.info("Flyway: {} modulspezifische Migrationsverzeichnisse gefunden: {}", discovered.size, discovered)
    }

    /** The module folders this instance migrates - all of them, minus the demo data outside demo mode. */
    internal fun moduleLocations(): List<String> = discoverModuleLocations().filter { demoMode || it !in DEMO_ONLY_LOCATIONS }

    /**
     * Every immediate subdirectory of `db/migration` that actually contains a migration. The
     * `classpath*:` prefix matters: it looks across all jars/classes dirs, so a module packaged
     * separately later is found the same way as one compiled into this build.
     */
    private fun discoverModuleLocations(): List<String> =
        PathMatchingResourcePatternResolver()
            .getResources("classpath*:db/migration/*/*.sql")
            .mapNotNull { resource ->
                val path = resource.url.toString()
                val marker = "db/migration/"
                val folder = path.substringAfterLast(marker, "").substringBeforeLast('/', "")
                folder.takeIf { it.isNotEmpty() && !it.contains('/') }
            }
            .distinct()
            .sorted()
            .map { "classpath:db/migration/$it" }

    private companion object {
        /**
         * The demo personas and their letters (V16): outside demo mode they would be real-looking
         * people in a database meant for real ones (review 2026-09-26, B-1). A database that already
         * ran them cannot be switched out of demo mode - Flyway then refuses the unknown applied
         * migration, which is the intended outcome: a demo database is no production database.
         */
        val DEMO_ONLY_LOCATIONS = setOf("classpath:db/migration/demo_seed")

        private val log = LoggerFactory.getLogger(ModuleMigrationLocations::class.java)
    }
}
