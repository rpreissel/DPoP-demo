package com.example.dpop.orchestrator.kc

import com.example.dpop.kcmigrate.buildAdminClient
import org.keycloak.representations.idm.RealmRepresentation
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** Which of the two login themes the realm shows (docs/ideen/keycloakify-statt-freemarker.md). */
enum class LoginTheme {
    /** The hand-written FreeMarker theme - the realm's `loginTheme` from the setup. */
    FREEMARKER,

    /** The Keycloakify theme; its parent is the FreeMarker theme, so pages it lacks still show. */
    KEYCLOAKIFY,
}

/**
 * Sets the realm's login theme in Keycloak - only that; which theme is wanted is the admin
 * switch's business (`LoginThemeSwitch`). Writes as `orchestrator-migration`
 * ([KeycloakMigrationToken]), the client that builds the realm anyway, so `orchestrator-admin`
 * keeps just manage-users and view-realm.
 */
@Component
@Profile("keycloak")
class KeycloakRealmLoginTheme(
    // Trust-all-SSLContext first, like every Keycloak client here (see KeycloakAdminClient).
    @Suppress("UNUSED_PARAMETER") tlsConfig: KeycloakTlsConfig,
    private val setupSource: ConfiguredKeycloakSetupSource,
    private val migrationToken: KeycloakMigrationToken,
    @Value("\${keycloak-migrate.base-url}") private val baseUrl: String,
) {

    fun apply(theme: LoginTheme) {
        val realm = setupSource.selected().realm
        val themeName = when (theme) {
            LoginTheme.FREEMARKER -> realm.loginTheme
            LoginTheme.KEYCLOAKIFY -> KEYCLOAKIFY_THEME
        }
        val kc = buildAdminClient(baseUrl, migrationToken::accessToken, insecure = true)
        try {
            // Only the one field: the admin API leaves everything not sent as it is.
            kc.realm(realm.realmName).update(RealmRepresentation().apply { loginTheme = themeName })
        } finally {
            kc.close()
        }
    }

    companion object {
        /** keycloak-theme/vite.config.ts `themeName` - the JAR in /opt/keycloak/providers. */
        const val KEYCLOAKIFY_THEME = "orchestrator-keycloakify"
    }
}
