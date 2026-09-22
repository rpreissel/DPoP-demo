package com.example.dpop.kcmigrate

import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.resource.RealmResource

/**
 * Empfänger von up { } / down { } innerhalb eines step(...)-Blocks. Implementiert RealmResource
 * per Delegation (clients(), roles(), toRepresentation(), ... funktionieren direkt am Empfänger,
 * ganz normale Admin-Client-API), plus remember/recall für Daten, die up erzeugt und down später
 * braucht - z.B. eine generierte ID, wenn es keinen stabilen Business-Key für einen Lookup gibt.
 * remember/recall schreiben sofort ins Realm-Attribut dieses einen Schritts, der Wert übersteht
 * damit auch einen komplett neuen Prozesslauf zwischen up und down.
 *
 * kc/realmName sind für den Fall da, dass ein Schritt etwas ausserhalb der RealmResource braucht -
 * das Realm selbst legt MigrationRunner an (siehe dessen ensureRealmExists), nicht ein Schritt.
 *
 * setup trägt alles, was am Skript dynamisch ist ([RealmSetup]) - ein Skript liest keine
 * Umgebungsvariable und schreibt keinen Host, keinen Port und keine Client-Id selbst hin.
 */
class StepContext internal constructor(
    val kc: Keycloak,
    val setup: RealmSetup,
    realm: RealmResource,
    private val memory: StepMemory,
) : RealmResource by realm {
    val realmName: String get() = setup.realmName

    fun remember(key: String, value: String) = memory.remember(key, value)
    fun recall(key: String): String = memory.recall(key)
    fun recallOrNull(key: String): String? = memory.recallOrNull(key)
}

internal class StepMemory(
    private val readAttr: (String) -> String?,
    private val writeAttr: (String, String) -> Unit,
    private val keyFor: (String) -> String,
) {
    fun remember(key: String, value: String) = writeAttr(keyFor(key), value)

    fun recall(key: String): String = recallOrNull(key)
        ?: error("Kein gemerkter Wert für \"$key\" - wurde der zugehörige up-Schritt schon erfolgreich ausgeführt?")

    fun recallOrNull(key: String): String? = readAttr(keyFor(key))
}
