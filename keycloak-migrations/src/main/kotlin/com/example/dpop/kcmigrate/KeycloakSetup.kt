package com.example.dpop.kcmigrate

import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Eine Keycloak-Umgebung, vollständig beschrieben - in zwei Hälften, und die Trennlinie ist die
 * wichtigste Aussage dieser Datei:
 *
 * - [realm] ist alles, was die Migration **ins Realm schreibt**. Ein geänderter Wert hier bedeutet
 *   ein anders gebautes Realm, das die schon erledigten Schritte nie wieder anfassen würden -
 *   deshalb baut [MigrationRunner] das Realm dann neu auf.
 * - [access] ist alles, was nur beschreibt, **wie man das fertige Realm erreicht**. Im Realm steht
 *   davon nichts, also kostet eine Änderung hier auch nichts: sie gilt beim nächsten Start.
 *
 * Das Migrationsskript bekommt ausschließlich [realm] zu sehen ([StepContext.setup]). Damit kann
 * ein Schritt gar nicht erst einen Wert verbauen, den der Reset nicht überwacht - die Regel
 * "was ins Realm geht, löst einen Reset aus" ist nicht geprüft, sondern unausdrückbar verletzbar.
 *
 * Aus demselben Satz speist sich beides: der Realm-Aufbau durch die Migration und der laufende
 * Betrieb des Orchestrators (Account-Sync, Peer-Auth, OIDC-Prüfung). Genau das ist der Zweck -
 * Aufbau und Betrieb meinen dasselbe Realm und dieselben Clients, also darf es sie nur einmal
 * geben.
 *
 * Die Werte stehen vollständig in der Konfiguration des Orchestrators
 * (`keycloak-setup.base`/`.variants` in application-keycloak.yml), nicht hier im Code: eine
 * weitere Umgebung ist damit eine Konfigurationsänderung. Diese Datei deklariert nur, WELCHE
 * Felder es gibt und was sie bedeuten.
 */
data class KeycloakSetup(
    val realm: RealmSetup,
    val access: KeycloakAccess,
) {
    companion object {
        /** Alle Feldnamen beider Hälften - was eine Basis nennen muss und eine Variante nennen darf. */
        val FIELD_NAMES: Set<String> get() = RealmSetup.FIELD_NAMES + KeycloakAccess.FIELD_NAMES

        /**
         * Baut den Satz aus flach benannten Werten (`realmName`, `keycloakBaseUrl`, ...) - so, wie
         * sie in der Konfiguration stehen. Welche Hälfte ein Feld trägt, muss die Konfiguration
         * nicht wissen.
         *
         * Fehlt ein Feld oder ist eines unbekannt, scheitert der Aufruf laut: ein halb
         * beschriebener Satz ist kein Satz, und ein Tippfehler bliebe sonst wirkungslos, statt
         * aufzufallen.
         */
        fun from(values: Map<String, String>): KeycloakSetup {
            (values.keys - FIELD_NAMES).sorted().firstOrNull()?.let {
                error("Unbekanntes Feld \"$it\" in der Keycloak-Konfiguration - bekannt sind: ${FIELD_NAMES.sorted().joinToString(", ")}")
            }
            return KeycloakSetup(
                realm = build(RealmSetup::class, values),
                access = build(KeycloakAccess::class, values),
            )
        }
    }
}

/**
 * Was die Migration ins Realm schreibt. **Jede Änderung an einem dieser Werte baut das Realm neu
 * auf** (siehe [MigrationRunner.resetOnSetupChange]) - ein halb umkonfiguriertes Realm wäre
 * schlimmer als ein frisches.
 */
data class RealmSetup(
    /** Das Realm, das aufgebaut und anschließend bedient wird. Alles hier lebt darin. */
    val realmName: String,
    /** Anzeigename dieses Realms auf den Login-Seiten. */
    val realmDisplayName: String,
    /**
     * Login-Theme des Realms beim Aufbau. Die Formulare der Extension leben darin; zur Laufzeit
     * schaltet der Orchestrator zwischen diesem und seinem Keycloakify-Kind-Theme um.
     */
    val loginTheme: String,

    /** Der öffentliche OIDC-Client, mit dem der Browser den Web-Kanal-Login fährt (PKCE, kein Secret). */
    val browserClientId: String,
    /** Zweiter Browser-Client, der denselben Login gegen den QR-Test-Flow fährt - nur für die Demo. */
    val qrTestClientId: String,
    /**
     * Service-Account-Client, mit dem der Orchestrator Keycloaks Admin-REST-API bedient
     * (Account-Sync: Nutzer anlegen und spiegeln). Trägt die Rolle `manage-users`.
     *
     * Kein Secret dazu: der Client authentisiert sich per `private_key_jwt` gegen den Schlüssel
     * unter `<orchestratorBaseUrl>/orchestrator/api/v1/kc/client-jwks/...` (ADR-25).
     */
    val adminApiClientId: String,
    /**
     * Bewusst rechtloser Client, ausschließlich für den Custom-Grant `urn:dpop-demo:account-token`
     * (App-Kanal-Login). Getrennt von [adminApiClientId], damit dieser Pfad nie Admin-Rechte sieht;
     * authentisiert sich auf demselben Weg.
     */
    val appTokenClientId: String,

    /**
     * Wohin Keycloak nach dem Login zurückleiten darf. Beide Browser-Clients teilen sich die
     * Liste: der QR-Test-Client ist dieselbe Anwendung, nur gegen einen anderen Flow.
     *
     * Ein Gegenstück für die erlaubten CORS-Origins braucht es nicht - die Clients tragen dafür
     * Keycloaks `+`, also "die Origins genau dieser Redirect-URIs".
     */
    val browserRedirectUris: List<String>,

    /**
     * Server-zu-Server: wie der Keycloak-Container den Orchestrator erreicht (kc-facade-Aufrufe).
     * Landet als Config-Property an der `orchestrator`-Komponente, die Extension liest ihn dort.
     */
    val orchestratorBaseUrl: String,
    /**
     * Derselbe Orchestrator, wie ein Browser ihn erreicht. Basis des QR-Deep-Links, der auf einem
     * fremden Gerät geöffnet wird - dort trägt [orchestratorBaseUrl] nicht. Ebenfalls
     * Config-Property der Komponente.
     */
    val publicOrchestratorBaseUrl: String,
    /** `iss` der Assertion, mit der Keycloaks Extension sich beim Orchestrator ausweist (ADR-7). */
    val peerAuthIssuer: String,
    /** `aud` derselben Assertion - wen sie adressiert, also den Orchestrator. */
    val peerAuthAudience: String,
) {
    /** Die Werte in der Form, in der [MigrationRunner] sie mit dem letzten Lauf vergleicht. */
    fun asMap(): Map<String, String> = fieldsOf(this)

    companion object {
        val FIELD_NAMES = fieldNamesOf(RealmSetup::class)
    }
}

/**
 * Wie der Orchestrator und der Browser das fertige Realm erreichen. Reine Laufzeit - im Realm
 * steht davon nichts, eine Änderung gilt einfach beim nächsten Start und löst **keinen** Reset aus.
 */
data class KeycloakAccess(
    /**
     * Server-zu-Server: wie der Orchestrator Keycloak erreicht (Admin-API, JWKS, Migration selbst).
     *
     * Zugangsdaten stehen hier keine: die Migration meldet sich als `orchestrator-migration` an,
     * einen Client im Master-Realm, den die Keycloak-Extension selbst anlegt - per signierter
     * Assertion (`private_key_jwt`), ohne Passwort.
     */
    val keycloakBaseUrl: String,
    /**
     * Dasselbe Keycloak, wie ein Browser es erreicht. Der Token-Issuer trägt diese Adresse, weil
     * der Browser sein Token von dort bekommt - geprüft wird trotzdem gegen [keycloakBaseUrl].
     */
    val publicKeycloakBaseUrl: String,
    /**
     * Ob der Orchestrator dem Zertifikat unter [keycloakBaseUrl] ohne Prüfung vertraut - nur für
     * ein selbstsigniertes Entwicklungszertifikat (`start-dev` im Compose-Stack). Gilt
     * ausschließlich für die Verbindungen zu Keycloak, nie JVM-weit (Review 2026-09, S-4). Die Basis
     * setzt `false`; eine Variante muss es ausdrücklich einschalten.
     */
    val trustSelfSignedCertificate: Boolean,
) {
    companion object {
        val FIELD_NAMES = fieldNamesOf(KeycloakAccess::class)
    }
}

// --- Gemeinsame Reflexions-Hilfen: ein neues Feld ist dadurch genau eine Zeile in seiner data
// class, ohne parallel gepflegte Namensliste, die auseinanderlaufen könnte.

private fun fieldNamesOf(type: KClass<*>): Set<String> =
    type.primaryConstructor!!.parameters.mapNotNull { it.name }.toSet()

private fun <T : Any> fieldsOf(value: T): Map<String, String> =
    value::class.memberProperties
        .sortedBy { it.name }
        .associate { property ->
            @Suppress("UNCHECKED_CAST")
            property.name to render((property as kotlin.reflect.KProperty1<T, *>).get(value))
        }

private fun <T : Any> build(type: KClass<T>, values: Map<String, String>): T {
    val constructor = type.primaryConstructor!!
    return constructor.callBy(
        constructor.parameters.associateWith { field ->
            val raw = values[field.name]
                ?: error("Feld \"${field.name}\" fehlt in der Keycloak-Konfiguration (keycloak-setup.base)")
            parse(field, raw)
        },
    )
}

/** Listenfelder als kommagetrennte Zeichenkette - dieselbe Form, in der sie in der Konfiguration stehen. */
private fun render(value: Any?): String = when (value) {
    is List<*> -> value.joinToString(",")
    else -> value.toString()
}

private fun parse(field: KParameter, raw: String): Any =
    when (field.type.classifier) {
        List::class -> raw.split(",").map(String::trim).filter(String::isNotEmpty)
        Boolean::class -> raw.trim().toBooleanStrictOrNull()
            ?: error("Feld \"${field.name}\" erwartet true oder false, nicht \"$raw\"")
        else -> raw
    }

/**
 * Woher ein benannter Satz kommt. Die Implementierung im Orchestrator liest ihn aus dessen eigener
 * Konfiguration (`keycloak-setup.*`); die Naht bleibt, damit die Sätze später aus einer anderen
 * Quelle kommen können, ohne dass Runner oder Skript davon etwas merken.
 */
fun interface KeycloakSetupSource {
    fun variant(name: String): KeycloakSetup
}
