package com.example.dpop.kcmigrate

import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation
import org.keycloak.representations.idm.RealmRepresentation
import org.keycloak.representations.userprofile.config.UPAttribute
import org.keycloak.representations.userprofile.config.UPAttributePermissions
import org.keycloak.representations.userprofile.config.UPConfig

/**
 * Gemeinsame Helfer für Migrationsskripte - über defaultImports (KcMigrationScript.kt) ohne
 * eigenen import in jeder .kc.kts-Datei sichtbar. Hier landet nur, was in mindestens zwei
 * Migrationsdateien gebraucht wird; Einmal-Bedarf bleibt lokal im jeweiligen Skript.
 */

/** get-mutate-update in einem Aufruf - das wiederkehrende Muster für Realm-Einstellungen. */
fun StepContext.updateRealm(mutate: RealmRepresentation.() -> Unit) {
    val rep = toRepresentation()
    rep.mutate()
    update(rep)
}

/**
 * Keycloaks User-Profile-Update ersetzt immer die GESAMTE Konfiguration, nicht additiv - deshalb
 * müssen username/email/firstName/lastName immer mit dabei sein. extraAttributes hängt weitere,
 * auf die genannten Rollen eingeschränkte Attribute an.
 */
fun upConfig(vararg extraAttributes: Pair<String, Set<String>>): UPConfig {
    fun attr(name: String, roles: Set<String> = setOf("admin", "user"), displayName: String? = null) =
        UPAttribute(name).apply {
            this.displayName = displayName
            permissions = UPAttributePermissions(roles, roles)
        }

    return UPConfig().apply {
        attributes = listOf(
            attr("username", displayName = "\${username}"),
            attr("email", displayName = "\${email}"),
            attr("firstName", displayName = "\${firstName}"),
            attr("lastName", displayName = "\${lastName}"),
        ) + extraAttributes.map { (name, roles) -> attr(name, roles) }
    }
}

fun StepContext.clientDbId(clientId: String): String = clients().findByClientId(clientId).first().id

/**
 * POST /clients ignoriert das secret-Feld beim Anlegen (Keycloak vergibt immer ein zufälliges) -
 * erst ein update() setzt einen gewünschten Wert durch. Als Helper hier statt inline im Skript,
 * weil derselbe Aufruf inline in einem step-Block das secret beobachtbar NICHT durchsetzt
 * (vermutlich ein Klassenlader-Effekt der Kotlin-Scripting-Engine auf die Jackson-Serialisierung -
 * siehe MigrationRunner.ensureRealmExists()s ähnlichen Fall) - über eine kompilierte Funktion
 * funktioniert es zuverlässig.
 */
fun StepContext.setClientSecret(clientId: String, secret: String) {
    val id = clientDbId(clientId)
    val rep = clients().get(id).toRepresentation()
    rep.setSecret(secret)
    clients().get(id).update(rep)
}
fun StepContext.scopeDbId(name: String): String = clientScopes().findAll().first { it.name == name }.id

/** Direktes Kind (Execution oder Subflow) eines Flow-Levels, gesucht über sein providerId oder alias. */
fun StepContext.childExecution(parentAlias: String, matches: (AuthenticationExecutionInfoRepresentation) -> Boolean): String =
    flows().getExecutions(parentAlias).first(matches).id

fun StepContext.setRequirement(parentAlias: String, id: String, requirement: String) {
    val info = flows().getExecutions(parentAlias).first { it.id == id }
    info.requirement = requirement
    flows().updateExecutions(parentAlias, info)
}

fun StepContext.topFlowId(alias: String): String = flows().getFlows().first { it.alias == alias }.id
