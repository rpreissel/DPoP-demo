package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.ext_stammdaten.ExtStammdatenService
import com.example.dpop.id_fsc.IdentFscToolHandler
import com.example.dpop.tool_api.BindingKey
import com.example.dpop.tool_api.ChannelResponse
import com.example.dpop.tool_api.ToolEndpoint
import com.example.dpop.tool_spi.ToolOutcome
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder

private const val IDENT_FSC_TOOL_ID = "ident-fsc"

data class IdentFscPatchRequest(
    val kvnr: String? = null,
    val name: String? = null,
    val vorname: String? = null,
    val fsc: String? = null
)

/**
 * toolId=ident-fsc (docs/06-ablaeufe.md #2). One controller owns activation, PATCH and GET for
 * this tool (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: ident-fsc", description = "KVNR/name/vorname/FSC identification")
@SecurityRequirement(name = "dpop")
class IdentFscToolController(
    private val handler: IdentFscToolHandler,
    private val extStammdatenService: ExtStammdatenService,
    private val toolEndpoint: ToolEndpoint
) {

    @PostMapping("/orchestrator/api/v1/app/channels/{channelSessionId}/tools/ident-fsc")
    @Operation(summary = "Activate ident-fsc", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.beginActivation(channelSessionId, bindingKeyRef, IDENT_FSC_TOOL_ID)
        val outcome = handler.start(context.toolSessionId)
        val response = toolEndpoint.applyOutcome(IDENT_FSC_TOOL_ID, outcome, context)
        val location = toolEndpoint.activationLocation(uriBuilder.build().toUri(), context.toolSessionId, IDENT_FSC_TOOL_ID)
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/ident-fsc")
    @Operation(
        summary = "Supply KVNR/name/vorname/FSC",
        description = "Only the fields being supplied or corrected need to be sent; all four together also resolves in one call."
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: IdentFscPatchRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef)
        toolEndpoint.requireCurrentTool(context, IDENT_FSC_TOOL_ID)

        val body = request ?: IdentFscPatchRequest()
        // Only the orchestrator may reference both id_fsc and ext_stammdaten (docs/08-projektrahmen.md #3).
        val personId = body.kvnr?.let { extStammdatenService.findPersonIdByKvnr(it) }
        val outcome = handler.patch(toolSessionId, body.kvnr, body.name, body.vorname, body.fsc, personId)

        return ResponseEntity.ok(toolEndpoint.applyOutcome(IDENT_FSC_TOOL_ID, outcome, context))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/ident-fsc")
    @Operation(summary = "Read the current ident-fsc state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef)
        val outcome = if (toolEndpoint.isCurrentTool(context, IDENT_FSC_TOOL_ID)) {
            checkNotNull(handler.read(toolSessionId) as? ToolOutcome.InProgress) {
                "read() must return InProgress while the tool is still current"
            }
        } else {
            null
        }
        return ResponseEntity.ok(toolEndpoint.buildReadResponse(toolSessionId, IDENT_FSC_TOOL_ID, context, outcome))
    }
}
