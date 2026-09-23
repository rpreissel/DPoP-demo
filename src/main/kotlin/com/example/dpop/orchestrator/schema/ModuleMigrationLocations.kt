package com.example.dpop.orchestrator.schema

import org.slf4j.LoggerFactory
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * Lets every module keep its own migrations in its own folder under `db/migration/`.
 *
 * The schema is already per module (`auth_sms.*`, `account.*`, ... - one schema each, V1), but the
 * migrations were not: `V1__schema.sql` creates the tables of all fourteen modules in one 589-line
 * file. Adding a method module therefore meant editing a file shared with every other module - the
 * one place module autonomy still broke, and the reason `V3__auth_kobil.sql` had already drifted
 * into a different convention on its own.
 *
 * Discovered rather than listed: a hard-coded `spring.flyway.locations` would just move the shared
 * file one level up - a central list a new module can be forgotten from, which is exactly the
 * failure mode the tool catalog and the retention sweep were already built to avoid. A module gets
 * its migrations run by creating the folder, and by nothing else.
 *
 * `classpath:db/migration` itself stays first and keeps V1-V4: they are history, already applied
 * everywhere, and splitting an applied migration would only change checksums for no gain. New
 * module-owned migrations go in the module's own folder.
 *
 * Versions remain ONE sequence across all folders - Flyway orders by version, not by location. Two
 * modules picking the same version is a startup failure ("Found more than one migration with
 * version X"), loud and immediate, which is the right outcome: it means two migrations disagree
 * about what runs first.
 */
@Configuration
class ModuleMigrationLocations {

    @Bean
    fun perModuleMigrationLocations(): FlywayConfigurationCustomizer = FlywayConfigurationCustomizer { configuration ->
        val discovered = discoverModuleLocations()
        if (discovered.isEmpty()) return@FlywayConfigurationCustomizer
        configuration.locations(*(configuration.locations.map { it.descriptor } + discovered).toTypedArray())
        log.info("Flyway: {} modulspezifische Migrationsverzeichnisse gefunden: {}", discovered.size, discovered)
    }

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
        private val log = LoggerFactory.getLogger(ModuleMigrationLocations::class.java)
    }
}
