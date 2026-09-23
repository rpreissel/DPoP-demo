package com.example.dpop.orchestrator.admin

import com.example.dpop.orchestrator.kernel.ChannelType
import com.example.dpop.orchestrator.tool.ToolAvailabilityService
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_spi.MethodRole
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ToolAvailabilityEntry(
    @field:Schema(example = "auth-sms") val toolId: String,
    @field:Schema(example = "sms") val method: String,
    /** Which kind of selection list the tool appears in - the order only matters among tools of one role. */
    @field:Schema(example = "IDENTIFIED_AUTH") val role: MethodRole,
    @field:Schema(example = "true") val enabled: Boolean,
    @field:Schema(example = "Wartungsfenster bis 18 Uhr") val reason: String?
)

/** One channel type's tools, in the order that channel offers them. */
data class ChannelToolAvailability(
    @field:Schema(example = "APP") val channel: ChannelType,
    val tools: List<ToolAvailabilityEntry>
)

data class ToolAvailabilityPutRequest(
    @field:Schema(example = "false") val enabled: Boolean,
    @field:Schema(example = "Wartungsfenster bis 18 Uhr") val reason: String? = null
)

data class ToolOrderPutRequest(
    @field:Schema(example = """["auth-password", "auth-sms"]""") val toolIds: List<String>
)

/**
 * The operator's say over tools, per channel type (docs/03-tool-architektur.md, availability):
 * switch a tool off for the App or the Web channel alone, and set the order each channel offers
 * its tools in. Both take effect on the next step of any journey, no redeploy needed. Behind the
 * admin login like everything under [ADMIN_API] (AdminSecurityConfig).
 */
@RestController
@RequestMapping("$ADMIN_API/tools")
@Tag(name = "Admin: tool availability", description = "Operator kill-switch and order for tools, per channel type")
class ToolAvailabilityController(
    private val toolAvailabilityService: ToolAvailabilityService,
    private val toolRegistry: ToolHandlerRegistry
) {

    @GetMapping("/availability")
    @Operation(summary = "Every catalog tool per channel type, in that channel's order, with its enabled state")
    fun list(): List<ChannelToolAvailability> {
        val catalog = toolRegistry.descriptors()
        val descriptorOf = catalog.associateBy { it.toolId }
        val disabled = toolAvailabilityService.disabledEntries()
        return ChannelType.entries.map { channel ->
            val off = disabled.filter { it.channel == channel }.associate { it.toolId!! to it.reason }
            ChannelToolAvailability(
                channel,
                toolAvailabilityService.ordered(channel, catalog.map { it.toolId }).map { toolId ->
                    val descriptor = descriptorOf.getValue(toolId)
                    ToolAvailabilityEntry(toolId.value, descriptor.method, descriptor.role, toolId.value !in off, off[toolId.value])
                }
            )
        }
    }

    @PutMapping("/{toolId}/availability/{channel}")
    @Operation(summary = "Enable or disable a tool for one channel type", description = "Takes effect on the next step computed for any channel of that type - no restart needed.")
    fun put(@PathVariable toolId: String, @PathVariable channel: ChannelType, @RequestBody request: ToolAvailabilityPutRequest) {
        if (request.enabled) toolAvailabilityService.enable(toolId, channel)
        else toolAvailabilityService.disable(toolId, channel, request.reason)
    }

    @PutMapping("/order/{channel}")
    @Operation(
        summary = "Set the order a channel type offers its tools in",
        description = "First entry is offered first. Tools left out move behind the listed ones. Applies to every selection " +
            "screen of that channel type (login, identification, enrollment) from its next step on."
    )
    fun putOrder(@PathVariable channel: ChannelType, @RequestBody request: ToolOrderPutRequest) {
        toolAvailabilityService.setOrder(channel, request.toolIds)
    }
}
