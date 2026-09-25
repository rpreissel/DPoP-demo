package com.example.dpop.orchestrator.admin

import com.example.dpop.orchestrator.kc.LoginTheme
import com.example.dpop.orchestrator.kernel.FeatureFlags
import com.example.dpop.orchestrator.session.FeatureFlagService
import com.example.dpop.orchestrator.tool.ToolAvailabilityService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** [channel]: APP or KEYCLOAK - a lock applies to one channel type. */
data class DisabledToolView(val toolId: String, val channel: String, val reason: String?)

/**
 * The real Keycloak as the BROWSER sees it - everything the Web channel's own OIDC client needs.
 * Derived by KeycloakSetupEnvironment from the selected setup variant, so the frontend carries no
 * copy of address, realm or client ids that could drift from what the migration built.
 * [baseUrl] is the public address (publicKeycloakBaseUrl), never the server-to-server one: under
 * compose that would be `https://keycloak:8443`, which no browser resolves.
 */
data class KeycloakInfo(
    val baseUrl: String,
    val realm: String,
    val browserClientId: String,
    val qrTestClientId: String,
    /** Which login theme the realm shows right now (admin page, "Anmeldeseiten"). */
    val loginTheme: LoginTheme
)

data class ServerInfo(
    /**
     * Set under the `keycloak` Spring profile (real Keycloak), null without it - then there is no
     * Web channel. One nullable block instead of a flag plus optional fields: "profile on, but no
     * address" cannot be expressed.
     */
    val keycloak: KeycloakInfo?,
    val registrationEnrollFirst: Boolean,
    val disabledTools: List<DisabledToolView>,
    /** `demo.disclosure` - whether responses carry the demo block (TANs, personas, ...). */
    val demoDisclosure: Boolean
)

/**
 * What the welcome page shows under "Server-Status": the conditions this demo runs under, read-only
 * and without login - it switches nothing, the switches are on the admin page.
 */
@RestController
@RequestMapping("$DEMO_API/server-info")
@Tag(name = "Demo: server info", description = "Read-only server status for the welcome page")
class ServerInfoController(
    private val environment: Environment,
    private val featureFlagService: FeatureFlagService,
    private val toolAvailabilityService: ToolAvailabilityService,
    private val loginThemeSwitch: ObjectProvider<LoginThemeSwitch>,
) {

    @GetMapping
    @Operation(summary = "Current server status")
    fun get(): ServerInfo {
        return ServerInfo(
            keycloak = if (environment.acceptsProfiles(Profiles.of("keycloak"))) keycloakInfo() else null,
            registrationEnrollFirst = featureFlagService.isEnabled(FeatureFlags.REGISTER_ENROLL_FIRST),
            disabledTools = toolAvailabilityService.disabledEntries().map { DisabledToolView(it.toolId!!, it.channel!!.name, it.reason) },
            demoDisclosure = environment.getProperty("demo.disclosure", Boolean::class.java, true)
        )
    }

    private fun keycloakInfo() = KeycloakInfo(
        baseUrl = environment.getRequiredProperty("keycloak-sync.public-base-url"),
        realm = environment.getRequiredProperty("keycloak-sync.realm"),
        browserClientId = environment.getRequiredProperty("keycloak-web.browser-client-id"),
        qrTestClientId = environment.getRequiredProperty("keycloak-web.qr-test-client-id"),
        loginTheme = loginThemeSwitch.getObject().current()
    )
}
