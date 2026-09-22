package com.example.dpop.orchestrator.kc

import com.example.dpop.kcmigrate.KeycloakSetup
import com.example.dpop.kcmigrate.KeycloakSetupSource
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Die Keycloak-Umgebungen, aus denen dieser Lauf eine auswaehlt - vollstaendig in
 * `application-keycloak.yml` deklariert: [base] nennt jedes Feld, jede Variante unter [variants]
 * nur ihre Abweichungen davon.
 *
 * Bewusst dort und nicht im Migrations-Modul: eine weitere Umgebung ist damit eine
 * Konfigurationsaenderung, keine Codeaenderung. Das Modul deklariert nur noch, welche Felder es
 * gibt ([KeycloakSetup]).
 */
@Component
@Profile("keycloak")
@ConfigurationProperties(prefix = ConfiguredKeycloakSetupSource.PREFIX)
class ConfiguredKeycloakSetupSource : KeycloakSetupSource {

    /** Name der Variante, die dieser Lauf anwendet. */
    var variant: String = ""

    /** Der vollstaendige Satz, gegen den jede Variante ihr Delta angibt. */
    var base: Map<String, String> = emptyMap()

    /** Variantenname -> Feldname -> Wert; jeweils nur die Abweichungen von [base]. */
    var variants: Map<String, Map<String, String>> = emptyMap()

    override fun variant(name: String): KeycloakSetup {
        val delta = variants[name]
            ?: error(
                "Unbekannte Keycloak-Variante \"$name\" - bekannt sind: " +
                    variants.keys.sorted().joinToString(", ").ifEmpty { "(keine deklariert)" },
            )
        return KeycloakSetup.from(base + delta)
    }

    /** Der Satz dieses Laufs. */
    fun selected(): KeycloakSetup = variant(variant)

    companion object {
        const val PREFIX = "keycloak-setup"
    }
}
