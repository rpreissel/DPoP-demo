package com.example.dpop.orchestrator.admin

import com.example.dpop.orchestrator.kc.LoginTheme
import com.example.dpop.orchestrator.kernel.FeatureFlags
import com.example.dpop.orchestrator.session.FeatureFlagService
import com.example.dpop.orchestrator.tool.ToolAvailabilityService
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.TimeUnit

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
    val demoDisclosure: Boolean,
    /** What the actuator reports on the management port, readable here without it. */
    val operations: OperationsInfo,
)

/** Health and metrics as `/actuator/health` and `/actuator/prometheus` report them (docs/07-betrieb.md Abschnitt 7). */
data class OperationsInfo(
    /** Overall status, UP/DOWN/OUT_OF_SERVICE/UNKNOWN. */
    val status: String,
    /** One entry per health component (db, keycloak, livenessState, ...), by name. */
    val components: List<HealthComponentView>,
    val metrics: List<MetricView>,
)

data class HealthComponentView(val name: String, val status: String)

/**
 * One of this project's own meters (`dpop.*`) per tag combination, or the outgoing HTTP calls
 * summed per target host (`http.client.requests`, [meanMillis] set).
 */
data class MetricView(val name: String, val tags: Map<String, String>, val value: Double, val meanMillis: Double? = null)

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
    private val healthEndpoint: HealthEndpoint,
    private val meterRegistry: MeterRegistry,
) {

    @GetMapping
    @Operation(summary = "Current server status")
    fun get(): ServerInfo {
        return ServerInfo(
            keycloak = if (environment.acceptsProfiles(Profiles.of("keycloak"))) keycloakInfo() else null,
            registrationEnrollFirst = featureFlagService.isEnabled(FeatureFlags.REGISTER_ENROLL_FIRST),
            disabledTools = toolAvailabilityService.disabledEntries().map { DisabledToolView(it.toolId!!, it.channel!!.name, it.reason) },
            demoDisclosure = environment.getProperty("demo.disclosure", Boolean::class.java, true),
            operations = operations(),
        )
    }

    private fun operations(): OperationsInfo {
        val health = healthEndpoint.health()
        val components = (health as? CompositeHealthDescriptor)?.components.orEmpty()
            .map { (name, component) -> HealthComponentView(name, component.status.code) }
            .sortedBy { it.name }
        return OperationsInfo(health.status.code, components, ownMeters() + outgoingCalls())
    }

    private fun ownMeters(): List<MetricView> =
        meterRegistry.meters.filter { it.id.name.startsWith("dpop.") }.mapNotNull { meter ->
            val value = when (meter) {
                is Counter -> meter.count()
                is Gauge -> meter.value()
                else -> return@mapNotNull null
            }
            MetricView(meter.id.name, meter.id.tags.associate { it.key to it.value }, value)
        }.sortedWith(compareBy({ it.name }, { it.tags.toString() }))

    private fun outgoingCalls(): List<MetricView> =
        meterRegistry.find("http.client.requests").timers()
            .groupBy { it.id.getTag("client.name") ?: "?" }
            .map { (host, timers) ->
                val count = timers.sumOf { it.count() }
                val totalMillis = timers.sumOf { it.totalTime(TimeUnit.MILLISECONDS) }
                MetricView("http.client.requests", mapOf("client.name" to host), count.toDouble(), if (count > 0) totalMillis / count else null)
            }
            .sortedBy { it.tags["client.name"] }

    private fun keycloakInfo() = KeycloakInfo(
        baseUrl = environment.getRequiredProperty("keycloak-sync.public-base-url"),
        realm = environment.getRequiredProperty("keycloak-sync.realm"),
        browserClientId = environment.getRequiredProperty("keycloak-web.browser-client-id"),
        qrTestClientId = environment.getRequiredProperty("keycloak-web.qr-test-client-id"),
        loginTheme = loginThemeSwitch.getObject().current()
    )
}
