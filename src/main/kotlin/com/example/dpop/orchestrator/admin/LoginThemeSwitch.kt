package com.example.dpop.orchestrator.admin

import com.example.dpop.orchestrator.kc.KeycloakRealmLoginTheme
import com.example.dpop.orchestrator.kc.LoginTheme
import com.example.dpop.orchestrator.domain.FeatureFlags
import com.example.dpop.orchestrator.session.FeatureFlagService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * The runtime switch between the two login themes (docs/ideen/keycloakify-statt-freemarker.md).
 * The orchestrator is the source of truth - the flag `keycloak-login-keycloakify`, no row meaning
 * FreeMarker - and the realm is made to follow it: on every [switchTo], and once at start after
 * the Keycloak migrations, because a realm the migration rebuilds starts again with the setup's
 * FreeMarker theme.
 *
 * @Order right after KeycloakMigrationRunnerStartup (HIGHEST_PRECEDENCE): the realm must exist.
 */
@Component
@Profile("keycloak")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class LoginThemeSwitch(
    private val featureFlagService: FeatureFlagService,
    private val realmLoginTheme: KeycloakRealmLoginTheme,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(LoginThemeSwitch::class.java)

    fun current(): LoginTheme =
        if (featureFlagService.isEnabled(FeatureFlags.KEYCLOAK_LOGIN_KEYCLOAKIFY)) LoginTheme.KEYCLOAKIFY else LoginTheme.FREEMARKER

    /**
     * Realm first, flag second: if Keycloak refuses, the flag still says what the realm shows and
     * the caller sees the error. Takes effect with the very next page Keycloak renders.
     */
    fun switchTo(theme: LoginTheme) {
        realmLoginTheme.apply(theme)
        featureFlagService.setEnabled(FeatureFlags.KEYCLOAK_LOGIN_KEYCLOAKIFY, theme == LoginTheme.KEYCLOAKIFY)
    }

    override fun run(args: ApplicationArguments) {
        val theme = current()
        // A login theme that cannot be set must not keep the orchestrator from starting - the
        // realm then simply keeps showing what it showed.
        runCatching { realmLoginTheme.apply(theme) }
            .onSuccess { log.info("Keycloak-Login-Theme auf {} abgeglichen.", theme) }
            .onFailure { log.warn("Keycloak-Login-Theme konnte nicht auf {} gesetzt werden", theme, it) }
    }
}
