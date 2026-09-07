package com.example.dpop.kcmigrate

import jakarta.ws.rs.NotFoundException
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.representations.idm.RealmRepresentation
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.valueOrThrow
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

private const val ATTR_PREFIX = "kcmig_"

/**
 * kotlin-scripting-jvm-host findet die Stdlib-Jar normalerweise, indem es "kotlin-stdlib*.jar" auf
 * java.class.path sucht - das funktioniert nur bei einem klassischen, flachen Classpath aus
 * Einzel-Jars (z.B. ./gradlew bootRun). In einer gepackten Spring-Boot-Fat-Jar (`java -jar app.jar`,
 * so laeuft der Orchestrator im compose-Setup) ist java.class.path nur "app.jar" - die Suche
 * schlaegt fehl ("Unable to find kotlin stdlib"), obwohl kotlin-stdlib ganz normal im
 * BOOT-INF/lib-Nested-Jar mitkommt. Fix: die Stdlib-Jar einmalig aus dem laufenden Classpath in
 * eine echte Datei extrahieren und Kotlin per System-Property direkt dorthin zeigen lassen.
 */
private val ensureKotlinStdlibJarProperty: Unit by lazy {
    if (System.getProperty("kotlin.java.stdlib.jar") == null) {
        val location = Unit::class.java.protectionDomain.codeSource.location
        val extracted = File.createTempFile("kotlin-stdlib", ".jar").apply { deleteOnExit() }
        location.openStream().use { input -> extracted.outputStream().use { input.copyTo(it) } }
        System.setProperty("kotlin.java.stdlib.jar", extracted.absolutePath)
    }
}

data class MigrationFile(val version: String, val description: String, val path: Path) {
    val checksumAttrKey get() = "$ATTR_PREFIX${version}__checksum"

    private val stepDonePattern = Regex("""${Regex.escape(ATTR_PREFIX)}${Regex.escape(version)}__step(\d+)$""")

    fun stepDoneAttrKey(stepIndex: Int) = "$ATTR_PREFIX${version}__step$stepIndex"
    fun stepDataAttrKey(stepIndex: Int, key: String) = "${stepDataPrefix(stepIndex)}$key"
    fun stepDataPrefix(stepIndex: Int) = "$ATTR_PREFIX${version}__step${stepIndex}__data__"

    fun matchStepDoneKey(attrKey: String): Int? = stepDonePattern.matchEntire(attrKey)?.groupValues?.get(1)?.toInt()

    companion object {
        private val PATTERN = Regex("""V(\d+)__(.+)\.kc\.kts""")

        fun parse(path: Path): MigrationFile? {
            val match = PATTERN.matchEntire(path.fileName.toString()) ?: return null
            return MigrationFile(match.groupValues[1], match.groupValues[2], path)
        }
    }
}

/** Lässt Aufrufer (z.B. ein Startup-Hook) erkennen, welche Datei fehlgeschlagen ist, um gezielt zurückzurollen. */
class MigrationStepFailedException(val fileVersion: String, val fileName: String, cause: Throwable) :
    RuntimeException("Migration $fileName fehlgeschlagen: ${cause.message}", cause)

data class MigrationStatus(
    val file: MigrationFile,
    val completedSteps: Int,
    val totalSteps: Int,
    val lastCompletedAt: String?,
    val checksumMismatch: Boolean,
) {
    val fullyApplied get() = totalSteps > 0 && completedSteps == totalSteps
}

/**
 * Wendet .kc.kts-Migrationsdateien Schritt für Schritt an (up) oder zurück (down). Pro Schritt
 * steht im Realm-Attribut "kcmig_<version>__step<i>" ein Zeitstempel, sobald er erfolgreich
 * gelaufen ist - kein eigenes Tracking-Schema nötig, die Historie hängt am Realm selbst und
 * wandert mit, wenn es exportiert/importiert wird. Bricht up() mitten in einer Datei ab, sind die
 * schon erledigten Schritte markiert; ein erneuter up()-Lauf setzt genau beim ersten offenen
 * Schritt fort, statt bereits Erledigtes (nicht zwingend idempotente up-Blöcke!) zu wiederholen.
 */
class MigrationRunner(
    private val kc: Keycloak,
    private val realmName: String,
    private val migrationsDir: Path,
) {
    private val realm: RealmResource get() = kc.realm(realmName)
    init {
        ensureKotlinStdlibJarProperty
    }

    private val compilationConfig = createJvmCompilationConfigurationFromTemplate<KcMigrationScript>()
    private val evaluationConfig = ScriptEvaluationConfiguration()
    private val host = BasicJvmScriptingHost()

    private fun discover(): List<MigrationFile> =
        Files.list(migrationsDir).use { stream ->
            stream.toList().mapNotNull { MigrationFile.parse(it) }
        }.sortedBy { it.version.toInt() }

    /** Bevor das Realm existiert, ist "noch nichts angewendet" - keine Fehlerbedingung. */
    private fun currentAttrs(): Map<String, String> =
        try {
            realm.toRepresentation().attributes.orEmpty()
        } catch (e: NotFoundException) {
            emptyMap()
        }

    /**
     * Realm-Bootstrap ist bewusst Runner-Infrastruktur, kein Migrationsschritt: der naheliegende
     * Weg über ein Skript (kc.realms().create(...) in einem step("realm anlegen")) scheitert
     * reproduzierbar mit "unable to read contents from stream", sobald derselbe Aufruf über die
     * Kotlin-Scripting-Engine statt aus normal kompiliertem Code läuft (Ursache nicht abschließend
     * geklärt, vermutlich ein Classloader/Jackson-Provider-Effekt der Scripting-Engine) - über
     * kompilierten Code (hier) funktioniert exakt derselbe Aufruf zuverlässig.
     */
    private fun ensureRealmExists() {
        if (runCatching { realm.toRepresentation() }.isSuccess) return
        kc.realms().create(RealmRepresentation().apply {
            realm = realmName
            setEnabled(true)
        })
    }

    /**
     * Flyway-artig, aber vereinfacht: statt eines Repair-Mechanismus wird bei einer geänderten,
     * bereits angewendeten Migrationsdatei das GESAMTE Realm gelöscht und aus allen Migrationen neu
     * aufgebaut. Für dieses Demo-/Dev-Setup ausreichend; in einer echten Produktivumgebung wäre das
     * zu grobschlächtig.
     */
    private fun resetOnChecksumConflict() {
        val attrs = currentAttrs()
        val conflicted = discover().filter { file ->
            val stored = attrs[file.checksumAttrKey]
            stored != null && stored != sha256(file.path)
        }
        if (conflicted.isEmpty()) return
        println(
            "Geänderte, bereits angewendete Migration(en): ${conflicted.joinToString { it.path.fileName.toString() }}" +
                " - lösche Realm '$realmName' und wende alle Migrationen neu an.",
        )
        realm.remove()
        ensureRealmExists()
    }

    private fun completedStepIndices(file: MigrationFile): Set<Int> =
        currentAttrs().keys.mapNotNull { file.matchStepDoneKey(it) }.toSet()

    fun status(): List<MigrationStatus> {
        val attrs = currentAttrs()
        return discover().map { file ->
            val script = loadScript(file)
            val doneIndices = attrs.keys.mapNotNull { file.matchStepDoneKey(it) }.toSet()
            val lastCompletedAt = doneIndices.mapNotNull { attrs[file.stepDoneAttrKey(it)] }.maxOrNull()
            val storedChecksum = attrs[file.checksumAttrKey]
            val mismatch = storedChecksum != null && storedChecksum != sha256(file.path)
            MigrationStatus(file, doneIndices.size, script.steps.size, lastCompletedAt, mismatch)
        }
    }

    fun up() {
        ensureRealmExists()
        resetOnChecksumConflict()
        var anyPending = false
        discover().forEach { file ->
            val script = loadScript(file)
            val done = completedStepIndices(file)
            if (done.size == script.steps.size) return@forEach
            anyPending = true
            if (done.isEmpty()) setAttr(file.checksumAttrKey, sha256(file.path))
            println("-> wende ${file.path.fileName} an")
            try {
                script.steps.forEachIndexed { i, step ->
                    if (i in done) {
                        println("   up: ${step.name} (schon erledigt, übersprungen)")
                        return@forEachIndexed
                    }
                    println("   up: ${step.name}")
                    step.up(stepContext(file, i))
                    setAttr(file.stepDoneAttrKey(i), Instant.now().toString())
                }
            } catch (e: Exception) {
                throw MigrationStepFailedException(file.version, file.path.fileName.toString(), e)
            }
            println("   ok (${script.steps.size} Schritte)")
        }
        if (!anyPending) println("Keine offenen Migrationen.")
    }

    fun down(version: String) {
        val file = discover().firstOrNull { it.version == version }
            ?: error("Keine Migration mit Version $version gefunden")
        val script = loadScript(file)
        val done = completedStepIndices(file)
        if (done.isEmpty()) {
            println("${file.path.fileName} ist nicht angewendet, nichts zu tun.")
            return
        }
        println("<- mache ${file.path.fileName} rückgängig")
        script.steps.withIndex().toList().asReversed().forEach { (i, step) ->
            if (i !in done) return@forEach
            val downBlock = step.down
                ?: error(
                    "${file.path.fileName}: step(\"${step.name}\") hat kein down { } - kann nicht automatisch " +
                        "zurückgerollt werden (${done.count { it < i }} vorangehende Schritte bleiben angewendet)",
                )
            println("   down: ${step.name}")
            downBlock(stepContext(file, i))
            clearStep(file, i)
        }
        if (completedStepIndices(file).isEmpty()) setAttr(file.checksumAttrKey, null)
        println("   ok")
    }

    private fun stepContext(file: MigrationFile, stepIndex: Int): StepContext {
        val memory = StepMemory(
            readAttr = { key -> currentAttrs()[key] },
            writeAttr = { key, value -> setAttr(key, value) },
            keyFor = { key -> file.stepDataAttrKey(stepIndex, key) },
        )
        return StepContext(kc, realmName, realm, memory)
    }

    private fun loadScript(file: MigrationFile): KcMigrationScript {
        val result = host.eval(file.path.toFile().toScriptSource(), compilationConfig, evaluationConfig)
        val evalResult = result.valueOrThrow()
        val instance = when (val rv = evalResult.returnValue) {
            is ResultValue.Value -> rv.scriptInstance
            is ResultValue.Unit -> rv.scriptInstance
            else -> null
        }
        return instance as? KcMigrationScript
            ?: error("${file.path.fileName} konnte nicht als KcMigrationScript geladen werden")
    }

    /** value == null löscht das Attribut wieder. */
    private fun setAttr(key: String, value: String?) {
        val rep = realm.toRepresentation()
        val attrs = (rep.attributes ?: mutableMapOf()).toMutableMap()
        if (value == null) attrs.remove(key) else attrs[key] = value
        rep.attributes = attrs
        realm.update(rep)
    }

    /** Entfernt die "erledigt"-Markierung eines Schritts und alle seine remember()-Daten. */
    private fun clearStep(file: MigrationFile, stepIndex: Int) {
        val rep = realm.toRepresentation()
        val attrs = (rep.attributes ?: mutableMapOf()).toMutableMap()
        attrs.remove(file.stepDoneAttrKey(stepIndex))
        attrs.keys.filter { it.startsWith(file.stepDataPrefix(stepIndex)) }.forEach { attrs.remove(it) }
        rep.attributes = attrs
        realm.update(rep)
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
