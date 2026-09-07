package com.example.dpop.kcmigrate

import org.keycloak.admin.client.Keycloak
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * CLI: status | up | down <version>
 *
 * Env vars (gleiche Namen wie infra/tofu/keycloak/run-keycloak-config.sh):
 *   KEYCLOAK_URL, KEYCLOAK_ADMIN, KEYCLOAK_ADMIN_PASSWORD, KEYCLOAK_REALM
 *   KC_MIGRATIONS_DIR (optional, default "migrations")
 */
fun main(args: Array<String>) {
    val command = args.getOrNull(0) ?: usage()

    val kc = buildAdminClient(
        url = requireEnv("KEYCLOAK_URL"),
        username = requireEnv("KEYCLOAK_ADMIN"),
        password = requireEnv("KEYCLOAK_ADMIN_PASSWORD"),
        insecure = System.getenv("KEYCLOAK_INSECURE") == "true",
    )
    try {
        val realmName = requireEnv("KEYCLOAK_REALM")
        val migrationsDir = Path.of(System.getenv("KC_MIGRATIONS_DIR") ?: "migrations")
        val runner = MigrationRunner(kc, realmName, migrationsDir)

        when (command) {
            "status" -> runner.status().forEach { s ->
                val marker = when {
                    s.fullyApplied -> "[x]"
                    s.completedSteps > 0 -> "[~]"
                    else -> "[ ]"
                }
                val progress = "${s.completedSteps}/${s.totalSteps} Schritte"
                val warning = if (s.checksumMismatch) "  !! Skript wurde nach dem Anwenden geändert" else ""
                println("$marker V${s.file.version}__${s.file.description}  $progress  ${s.lastCompletedAt.orEmpty()}$warning")
            }
            "up" -> runner.up()
            "down" -> runner.down(args.getOrNull(1) ?: usage())
            else -> usage()
        }
    } finally {
        kc.close()
    }
}

private fun usage(): Nothing {
    println("Nutzung: status | up | down <version>")
    exitProcess(1)
}

private fun requireEnv(name: String): String =
    System.getenv(name) ?: error("$name ist nicht gesetzt")
