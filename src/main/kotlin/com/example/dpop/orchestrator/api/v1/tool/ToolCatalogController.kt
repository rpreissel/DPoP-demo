package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ToolCatalogEntry(
    @field:Schema(example = "auth-sms") val toolId: String,
    @field:Schema(example = "sms") val method: String,
    @field:Schema(example = "DEVICE_AUTH") val role: String
)

/**
 * The public, read-only tool catalog - used by a client to know which toolIds it could possibly
 * declare as `availableTools` on `POST /channels` (docs/03-tool-architektur.md, availability), and
 * by the demo admin UI to list what can be toggled.
 */
@RestController
@RequestMapping("/orchestrator/api/v1/tools")
@Tag(name = "Tool catalog", description = "The full set of registered tools, independent of any journey")
class ToolCatalogController(private val toolRegistry: ToolHandlerRegistry) {

    @GetMapping("/catalog")
    @Operation(
        summary = "List every registered tool",
        description = "No auth, no channel required - purely descriptive.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(examples = [ExampleObject(value = """
                    [
                      {"toolId": "ident-fsc", "method": "fsc", "role": "IDENTIFICATION"},
                      {"toolId": "enroll-sms", "method": "sms", "role": "ENROLLMENT"},
                      {"toolId": "auth-sms", "method": "sms", "role": "DEVICE_AUTH"},
                      {"toolId": "auth-sms-lookup", "method": "sms", "role": "LOOKUP_AUTH"}
                    ]
                """)])]
            )
        ]
    )
    fun catalog(): List<ToolCatalogEntry> =
        toolRegistry.descriptors().map { ToolCatalogEntry(it.toolId, it.method, it.role.name) }
}
