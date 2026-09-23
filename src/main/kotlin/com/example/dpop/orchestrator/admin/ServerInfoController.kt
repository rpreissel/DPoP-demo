package com.example.dpop.orchestrator.admin

import com.example.dpop.orchestrator.kernel.FeatureFlags
import com.example.dpop.orchestrator.session.FeatureFlagService
import com.example.dpop.orchestrator.tool.ToolAvailabilityService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class DisabledToolView(val toolId: String, val reason: String?)

data class ServerInfo(
    /** true under the `keycloak` Spring profile (real Keycloak), false without it - then there is no Web channel. */
    val keycloakProfile: Boolean,
    /** Only set under the `keycloak` profile, derived by KeycloakSetupEnvironment. */
    val keycloakBaseUrl: String?,
    val keycloakRealm: String?,
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
) {

    @GetMapping
    @Operation(summary = "Current server status")
    fun get(): ServerInfo {
        val keycloak = environment.acceptsProfiles(Profiles.of("keycloak"))
        return ServerInfo(
            keycloakProfile = keycloak,
            keycloakBaseUrl = if (keycloak) environment.getProperty("keycloak-sync.base-url") else null,
            keycloakRealm = if (keycloak) environment.getProperty("keycloak-sync.realm") else null,
            registrationEnrollFirst = featureFlagService.isEnabled(FeatureFlags.REGISTER_ENROLL_FIRST),
            disabledTools = toolAvailabilityService.disabledEntries().map { (toolId, reason) -> DisabledToolView(toolId, reason) },
            demoDisclosure = environment.getProperty("demo.disclosure", Boolean::class.java, true)
        )
    }
}
