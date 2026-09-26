package com.example.dpop.orchestrator.tool

import com.example.dpop.orchestrator.domain.ChannelType
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** One channel type's preset: its tool order and the tools switched off in it. */
data class ChannelToolDefaults(
    val order: List<String> = emptyList(),
    val disabled: List<String> = emptyList()
)

/**
 * The demo's preset tool settings per channel type (`demo.tool-defaults` in application.yml) -
 * what the admin page shows on a fresh start and what "Demo zurücksetzen" goes back to.
 */
@ConfigurationProperties(prefix = "demo.tool-defaults")
data class ToolDefaults(val channels: Map<ChannelType, ChannelToolDefaults> = emptyMap())

/**
 * Applies [ToolDefaults] at startup - only when no tool setting exists yet, so an operator's own
 * changes survive a restart against a persistent database.
 */
@Component
class ToolDefaultsInitializer(private val toolAvailabilityService: ToolAvailabilityService) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        if (!toolAvailabilityService.hasAnySetting()) toolAvailabilityService.applyDefaults()
    }
}
