package com.example.dpop.kcmigrate

import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.representations.idm.RealmRepresentation
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.valueOrThrow
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

private const val ATTR_PREFIX = "kcmig_"
private const val SETUP_ATTR_PREFIX = "${ATTR_PREFIX}setup__"

/** Der zuletzt angewendete Wert eines KeycloakSetup-Feldes, als Realm-Attribut wie alles andere hier. */
private fun setupAttrKey(name: String) = "$SETUP_ATTR_PREFIX$name"

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

/**
 * Eine Migration, wie der Runner sie braucht: ihr Name (aus dem die Version kommt) und ihr Text.
 *
 * Bewusst der TEXT und kein Dateipfad: die Skripte liegen als Ressourcen im Jar, nicht als Dateien
 * daneben. Sie sind damit Teil genau des Artefakts, dessen Code sie voraussetzen - ein Jar und ein
 * Verzeichnis, die getrennt ausgeliefert werden, koennen auseinanderlaufen. Woher der Text kommt,
 * entscheidet der Aufrufer; der Runner kennt kein Dateisystem mehr.
 */
data class MigrationFile(val version: String, val description: String, val name: String, val text: String) {
    val checksumAttrKey get() = "$ATTR_PREFIX${version}__checksum"

    private val stepDonePattern = Regex("""${Regex.escape(ATTR_PREFIX)}${Regex.escape(version)}__step(\d+)$""")

    fun stepDoneAttrKey(stepIndex: Int) = "$ATTR_PREFIX${version}__step$stepIndex"
    fun stepDataAttrKey(stepIndex: Int, key: String) = "${stepDataPrefix(stepIndex)}$key"
    fun stepDataPrefix(stepIndex: Int) = "$ATTR_PREFIX${version}__step${stepIndex}__data__"

    fun matchStepDoneKey(attrKey: String): Int? = stepDonePattern.matchEntire(attrKey)?.groupValues?.get(1)?.toInt()

    companion object {
        private val PATTERN = Regex("""V(\d+)__(.+)\.kc\.kts""")

        /** Null, wenn der Name nicht dem V<n>__<beschreibung>.kc.kts-Schema folgt - dann ist es keine Migration. */
        fun parse(name: String, text: String): MigrationFile? {
            val match = PATTERN.matchEntire(name) ?: return null
            return MigrationFile(match.groupValues[1], match.groupValues[2], name, text)
        }
    }
}

/** Lässt Aufrufer (z.B. ein Startup-Hook) erkennen, welche Datei fehlgeschlagen ist, um gezielt zurückzurollen. */
class MigrationStepFailedException(val fileVersion: String, val fileName: String, cause: Throwable) :
    RuntimeException("Migration $fileName fehlgeschlagen: ${cause.message}", cause)

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
    private val setup: RealmSetup,
    private val migrations: List<MigrationFile>,
    /**
     * Nach dem Anlegen des Realms aufgerufen: das bis dahin verwendete Token muss weg. Keycloak
     * prueft Admin-Rechte an den Rollen IM Token, und die Rechte auf das neue Realm kommen erst mit
     * seinem Anlegen hinzu - ein vorher ausgestelltes Token kennt sie nicht, der naechste Aufruf
     * gegen das Realm scheiterte mit 403.
     */
    private val onRealmCreated: () -> Unit = {},
    /**
     * Ob eine geänderte Migration oder ein geändertes Setup-Feld das Realm löschen und neu aufbauen
     * DARF. Ein Neuaufbau nimmt Sitzungen, alle `sub`-IDs und jedes Credential am Nutzer mit
     * (Review 2026-09, M-10) - ohne diesen Schalter bricht der Lauf stattdessen mit einer Meldung ab,
     * die den Grund nennt. Ausdrücklich, ohne Voreinstellung: der Aufrufer entscheidet.
     */
    private val allowRealmReset: Boolean,
) {
    private val realmName: String get() = setup.realmName
    private val realm: RealmResource get() = kc.realm(realmName)
    init {
        ensureKotlinStdlibJarProperty
    }

    private val compilationConfig = createJvmCompilationConfigurationFromTemplate<KcMigrationScript>()
    private val evaluationConfig = ScriptEvaluationConfiguration()
    private val host = BasicJvmScriptingHost()

    /** Die Migrationen in Versionsreihenfolge - der Aufrufer reicht sie herein, siehe [MigrationFile]. */
    private fun discover(): List<MigrationFile> = migrations.sortedBy { it.version.toInt() }

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
        try {
            realm.toRepresentation()
            return
        } catch (e: NotFoundException) {
            // Gibt es noch nicht - unten anlegen.
        } catch (e: ForbiddenException) {
            // Ohne diese Unterscheidung ginge es unten mit "Realm anlegen" weiter und scheiterte
            // an einem 403 oder 409 - mit einer Ursache, die niemand darin erkennt.
            throw IllegalStateException(
                "Realm '$realmName': die Migration hat keine Rechte darauf. Sie meldet sich als " +
                    "orchestrator-migration im Master-Realm an und verwaltet nur Realms, die sie selbst angelegt " +
                    "hat (Rolle create-realm, MigrationClientBootstrapFactory). Ein von Hand angelegtes Realm " +
                    "dieses Namens muss geloescht oder dem Service Account freigegeben werden.",
                e,
            )
        }
        kc.realms().create(RealmRepresentation().apply {
            realm = realmName
            setEnabled(true)
        })
        onRealmCreated()
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
            stored != null && stored != sha256(file.text)
        }
        if (conflicted.isEmpty()) return
        resetRealm(
            "Geänderte, bereits angewendete Migration(en): ${conflicted.joinToString { it.name }}",
        )
    }

    /**
     * Ein geänderter Parameterwert wiegt genauso schwer wie eine geänderte Migrationsdatei: die
     * Schritte, die ihn verbaut haben, gelten als erledigt und würden ihn nie wieder anfassen -
     * das Realm liefe stillschweigend mit dem alten Wert weiter. Also derselbe Reset.
     *
     * Verglichen werden ausschließlich Werte, die schon einmal gespeichert wurden. Ein später
     * HINZUGEFÜGTES Feld hat keinen gespeicherten Vorgänger, löst also keinen Reset aus und zwingt
     * zu keiner Änderung an den bestehenden Skripten. Der Preis dafür: ein Feld, das gar kein
     * Skript verwendet, löst bei Wertänderung trotzdem einen Reset aus.
     */
    private fun resetOnSetupChange() {
        val attrs = currentAttrs()
        val changed = setup.asMap().filter { (name, value) ->
            val stored = attrs[setupAttrKey(name)]
            stored != null && stored != value
        }
        if (changed.isEmpty()) return
        resetRealm("Geändertes Setup-Feld: ${changed.keys.joinToString()}")
    }

    /** Nach einem erfolgreichen Lauf ist der aktuelle Satz der, gegen den beim nächsten Mal verglichen wird. */
    private fun rememberSetup() {
        setup.asMap().forEach { (name, value) ->
            if (currentAttrs()[setupAttrKey(name)] != value) setAttr(setupAttrKey(name), value)
        }
    }

    private fun resetRealm(reason: String) {
        if (!allowRealmReset) throw RealmResetRefusedException(realmName, reason)
        println("$reason - lösche Realm '$realmName' und wende alle Migrationen neu an.")
        realm.remove()
        ensureRealmExists()
    }

    private fun completedStepIndices(file: MigrationFile): Set<Int> =
        currentAttrs().keys.mapNotNull { file.matchStepDoneKey(it) }.toSet()

    fun up() {
        ensureRealmExists()
        resetOnChecksumConflict()
        resetOnSetupChange()
        var anyPending = false
        discover().forEach { file ->
            val script = loadScript(file)
            val done = completedStepIndices(file)
            if (done.size == script.steps.size) return@forEach
            anyPending = true
            if (done.isEmpty()) setAttr(file.checksumAttrKey, sha256(file.text))
            println("-> wende ${file.name} an")
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
                throw MigrationStepFailedException(file.version, file.name, e)
            }
            println("   ok (${script.steps.size} Schritte)")
        }
        rememberSetup()
        if (!anyPending) println("Keine offenen Migrationen.")
    }

    fun down(version: String) {
        val file = discover().firstOrNull { it.version == version }
            ?: error("Keine Migration mit Version $version gefunden")
        val script = loadScript(file)
        val done = completedStepIndices(file)
        if (done.isEmpty()) {
            println("${file.name} ist nicht angewendet, nichts zu tun.")
            return
        }
        println("<- mache ${file.name} rückgängig")
        script.steps.withIndex().toList().asReversed().forEach { (i, step) ->
            if (i !in done) return@forEach
            val downBlock = step.down
                ?: error(
                    "${file.name}: step(\"${step.name}\") hat kein down { } - kann nicht automatisch " +
                        "zurückgerollt werden (${done.count { it < i }} vorangehende Schritte bleiben angewendet)",
                )
            println("   down: ${step.name}")
            downBlock(stepContext(file, i))
            clearStep(file, i)
        }
        if (completedStepIndices(file).isEmpty()) setAttr(file.checksumAttrKey, null)
        // Sobald gar nichts mehr angewendet ist, darf auch kein Parameterstand mehr behauptet
        // werden - sonst vergliche der nächste up()-Lauf gegen Werte, zu denen es kein Realm gibt.
        if (discover().none { completedStepIndices(it).isNotEmpty() }) {
            currentAttrs().keys.filter { it.startsWith(SETUP_ATTR_PREFIX) }.forEach { setAttr(it, null) }
        }
        println("   ok")
    }

    private fun stepContext(file: MigrationFile, stepIndex: Int): StepContext {
        val memory = StepMemory(
            readAttr = { key -> currentAttrs()[key] },
            writeAttr = { key, value -> setAttr(key, value) },
            keyFor = { key -> file.stepDataAttrKey(stepIndex, key) },
        )
        return StepContext(kc, setup, realm, memory)
    }

    private fun loadScript(file: MigrationFile): KcMigrationScript {
        val result = host.eval(file.text.toScriptSource(file.name), compilationConfig, evaluationConfig)
        val evalResult = result.valueOrThrow()
        val instance = when (val rv = evalResult.returnValue) {
            is ResultValue.Value -> rv.scriptInstance
            is ResultValue.Unit -> rv.scriptInstance
            else -> null
        }
        return instance as? KcMigrationScript
            ?: error("${file.name} konnte nicht als KcMigrationScript geladen werden")
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

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Ein Neuaufbau des Realms wäre nötig, ist aber nicht freigegeben (`allowRealmReset`). Der Start
 * bricht ab, statt Sitzungen, Nutzer-IDs und Credentials stillschweigend zu verwerfen.
 */
class RealmResetRefusedException(realmName: String, reason: String) : IllegalStateException(
    "$reason - das Realm '$realmName' müsste dafür gelöscht und neu aufgebaut werden. Das verwirft " +
        "Sitzungen, alle Nutzer-IDs (sub) und jedes Credential am Nutzer und ist nur im Demomodus " +
        "erlaubt. Die Änderung als neue Migration schreiben statt eine angewendete zu ändern, oder " +
        "das Realm bewusst von Hand entfernen."
)
