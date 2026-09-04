package com.example.dpop.orchestrator.session

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.api.FlywayException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File
import javax.sql.DataSource

/**
 * This is a demo project with no real users or data to protect, and its migration history gets
 * rewritten in place from time to time (squashing an old column-by-column patch into the
 * original CREATE TABLE once nobody needs the intermediate shape anymore, docs: see the
 * `auth_context` table's history). That makes Flyway's own checksum/history validation - correct
 * and load-bearing for a real deployment - actively hostile here: any local H2 file created
 * before such a rewrite fails validation on next boot with no way to proceed short of a manual
 * `rm -rf data/`. Since there's nothing in that file worth protecting, recover automatically
 * instead: if the normal migrate() fails, drop the H2 database file and migrate a fresh one.
 * Never do this in front of a database anyone actually depends on.
 */
@Configuration
class FlywayResetConfig {
    private val log = LoggerFactory.getLogger(FlywayResetConfig::class.java)

    @Bean
    fun flywayMigrationStrategy(@Value("\${spring.datasource.url}") jdbcUrl: String, dataSource: DataSource): FlywayMigrationStrategy =
        FlywayMigrationStrategy { flyway ->
            try {
                flyway.migrate()
            } catch (e: FlywayException) {
                log.warn(
                    "Flyway migration failed ({}) - deleting the H2 database file and recreating it " +
                        "from scratch. Demo-only recovery: this discards all existing data.",
                    e.message
                )
                deleteH2DatabaseFiles(jdbcUrl)
                // H2 maps a file-mode database into an already-open Connection's own memory, so
                // deleting the file on disk does nothing for a Connection Hikari opened before
                // this ran (see HikariPool-1's very first "Added connection" log line, well
                // before Flyway's own first migrate() attempt) - it would keep serving the OLD,
                // now-deleted database straight out of that mapping. Evict the pool so the retry
                // below is forced to open a genuinely fresh Connection against the fresh file.
                (dataSource as? HikariDataSource)?.hikariPoolMXBean?.softEvictConnections()
                flyway.migrate()
            }
        }

    private fun deleteH2DatabaseFiles(jdbcUrl: String) {
        val prefix = "jdbc:h2:file:"
        if (!jdbcUrl.startsWith(prefix)) return
        val path = jdbcUrl.removePrefix(prefix).substringBefore(";")
        listOf(".mv.db", ".trace.db").forEach { suffix ->
            val file = File("$path$suffix")
            if (file.exists() && file.delete()) {
                log.warn("Deleted {}", file.path)
            }
        }
    }
}
