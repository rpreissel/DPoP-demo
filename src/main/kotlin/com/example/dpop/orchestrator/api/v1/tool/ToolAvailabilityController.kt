package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.orchestrator.tool.ToolAvailabilityService
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ToolAvailabilityEntry(val toolId: String, val method: String, val enabled: Boolean, val reason: String?)

data class ToolAvailabilityPutRequest(val enabled: Boolean, val reason: String? = null)

/**
 * The backend-side kill-switch for a tool (docs/03-tool-architektur.md, availability): global,
 * takes effect immediately on the next step of any journey, no redeploy needed. Demo scope
 * deliberately: no auth guard here yet, since this project has no admin security anywhere else -
 * do not expose this beyond a trusted operator network as-is.
 */
@RestController
@RequestMapping("/orchestrator/api/v1/admin/tools")
@Tag(name = "Admin: tool availability", description = "Operator kill-switch for individual tools - no auth guard yet (demo scope)")
class ToolAvailabilityController(
    private val toolAvailabilityService: ToolAvailabilityService,
    private val toolRegistry: ToolHandlerRegistry
) {

    @GetMapping("/availability")
    @Operation(summary = "List every catalog tool with its current backend-enabled state")
    fun list(): List<ToolAvailabilityEntry> {
        val disabled = toolAvailabilityService.disabledEntries()
        return toolRegistry.descriptors()
            .sortedWith(compareBy({ it.method }, { it.toolId }))
            .map { ToolAvailabilityEntry(it.toolId, it.method, it.toolId !in disabled, disabled[it.toolId]) }
    }

    @PutMapping("/{toolId}/availability")
    @Operation(summary = "Enable or disable a tool", description = "Takes effect on the next step computed for any channel - no restart needed.")
    fun put(@PathVariable toolId: String, @RequestBody request: ToolAvailabilityPutRequest) {
        if (request.enabled) toolAvailabilityService.enable(toolId) else toolAvailabilityService.disable(toolId, request.reason)
    }
}
