package com.example.dpop.kcmigrate

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

/**
 * Kompilierungs-Konfiguration für .kc.kts-Dateien: dependenciesFromCurrentContext(wholeClasspath)
 * gibt den Skripten denselben Klassenpfad wie diesem Modul mit (Admin-Client, kein @file:DependsOn
 * nötig); defaultImports macht die *Representation-Klassen ohne eigene Import-Zeile im Skript
 * verfügbar - wer mehr braucht, schreibt sich dafür einen normalen import in die .kc.kts-Datei.
 */
object KcMigrationScriptConfig : ScriptCompilationConfiguration({
    jvm {
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
    defaultImports(
        "org.keycloak.representations.idm.*",
        "org.keycloak.representations.userprofile.config.*",
        "org.keycloak.common.util.MultivaluedHashMap",
        "com.example.dpop.kcmigrate.*",
    )
})

/** Empfänger von step("...") { } - up/down werden hier verschachtelt, nicht über einen Namen gematcht. */
class KcMigrationStep {
    internal var upBlock: (StepContext.() -> Unit)? = null
    internal var downBlock: (StepContext.() -> Unit)? = null

    fun up(block: StepContext.() -> Unit) {
        upBlock = block
    }

    fun down(block: StepContext.() -> Unit) {
        downBlock = block
    }
}

internal class ResolvedStep(
    val name: String,
    val up: StepContext.() -> Unit,
    val down: (StepContext.() -> Unit)?,
)

/**
 * Implizite Basisklasse jeder V<n>__beschreibung.kc.kts-Datei. Ein Skript besteht aus einem oder
 * mehreren step(...) { }-Blöcken; up/down darin bekommen einen StepContext gereicht, der die
 * RealmResource des Admin-Clients direkt implementiert (clients(), roles(), ... ohne Umweg) und
 * zusätzlich remember/recall für Daten, die up erzeugt und down später braucht:
 *
 *   step("client anlegen") {
 *       up {
 *           clients().create(ClientRepresentation().apply { clientId = "..." }).close()
 *       }
 *       down {
 *           clients().findByClientId("...").firstOrNull()?.let { clients().get(it.id).remove() }
 *       }
 *   }
 *
 * Die Kopplung von up/down ist rein strukturell (Verschachtelung im selben step-Block) - der Name
 * ist nur ein Label fürs Log, kein Schlüssel zum Zuordnen. MigrationRunner führt die Schritte
 * einer Datei in Deklarationsreihenfolge aus (up), beim Zurückrollen (down) in genau umgekehrter
 * Reihenfolge, und merkt sich pro Schritt in einem Realm-Attribut, ob er schon gelaufen ist -
 * ein abgebrochener up-Lauf setzt beim nächsten Mal nur bei dem Schritt fort, der noch fehlt.
 * Fehlt einem step das down { }, kann diese Migration ab diesem Schritt nicht automatisch
 * zurückgerollt werden.
 */
@KotlinScript(
    fileExtension = "kc.kts",
    compilationConfiguration = KcMigrationScriptConfig::class,
)
abstract class KcMigrationScript {
    internal val steps = mutableListOf<ResolvedStep>()

    fun step(name: String, configure: KcMigrationStep.() -> Unit) {
        val built = KcMigrationStep().apply(configure)
        val upBlock = built.upBlock ?: error("step(\"$name\"): kein up { } definiert")
        steps.add(ResolvedStep(name, upBlock, built.downBlock))
    }
}
