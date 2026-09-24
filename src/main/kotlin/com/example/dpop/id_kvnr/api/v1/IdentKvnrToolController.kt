package com.example.dpop.id_kvnr.api.v1

import com.example.dpop.id_kvnr.internal.IdentKvnrToolHandler
import com.example.dpop.tool_api.BindingKey
import com.example.dpop.tool_api.ChannelResponse
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_api.ToolEndpoint
import com.example.dpop.tool_api.normalizeKvnr
import com.example.dpop.tool_spi.ToolOutcome
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
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
import com.example.dpop.tool_api.API_V1

private const val IDENT_KVNR_TOOL_ID = "ident-kvnr"

data class IdentKvnrPatchRequest(
    @field:Schema(example = "A123456789") val kvnr: String? = null,
    /** Only without a KVNR (a Partner, ADR-34) - the client asks for the KVNR first, then for this. */
    @field:Schema(example = "P000000004") val partnernr: String? = null
)

/**
 * toolId=ident-kvnr. One controller owns activation, PATCH and GET for this tool
 * (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: Versichertennummer", description = "Ordnet eine bereits bezeugte Identität der Registerperson zu")
@SecurityRequirement(name = "dpop")
class IdentKvnrToolController(
    private val handler: IdentKvnrToolHandler,
    private val personDirectory: PersonDirectory,
    private val toolEndpoint: ToolEndpoint
) {

    @PostMapping("$API_V1/channels/{channelSessionId}/tools/ident-kvnr")
    @Operation(
        summary = "Activate ident-kvnr",
        description = "No request body: toolId already carries kind and method.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "ident-kvnr", "step": "input", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
                    }
                """)])]
            )
        ]
    )
    fun activate(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.beginActivation(channelSessionId, bindingKeyRef, IDENT_KVNR_TOOL_ID)
        val outcome = handler.start(context.toolSessionId)
        val response = toolEndpoint.applyOutcome(context, outcome)
        val location = toolEndpoint.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("$API_V1/tools/{toolSessionId}/ident-kvnr")
    @Operation(
        summary = "Supply the Versichertennummer - or, without one, the Partnernummer",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(name = "Assigned - the run finishes", value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "orchestrator", "context": "enrollment", "step": "selectMethod"}
                    }
                """)])]
            )
        ]
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: IdentKvnrPatchRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadCurrent(toolSessionId, bindingKeyRef, IDENT_KVNR_TOOL_ID)

        val body = request ?: IdentKvnrPatchRequest()
        // The KVNR comes first (ADR-34): given, it alone decides; the Partnernummer only counts without one.
        val personId = when {
            !body.kvnr.isNullOrBlank() -> personDirectory.findPersonIdByKvnr(normalizeKvnr(body.kvnr))
            !body.partnernr.isNullOrBlank() -> personDirectory.findPersonIdByPartnernr(body.partnernr)
            else -> null
        }
        val outcome = handler.patch(toolSessionId, body.kvnr, body.partnernr, personId)

        return ResponseEntity.ok(toolEndpoint.applyOutcome(context, outcome))
    }

    @GetMapping("$API_V1/tools/{toolSessionId}/ident-kvnr")
    @Operation(summary = "Read the current ident-kvnr state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, IDENT_KVNR_TOOL_ID)
        val outcome = if (toolEndpoint.isCurrentTool(context)) {
            checkNotNull(handler.read(toolSessionId) as? ToolOutcome.InProgress) {
                "read() must return InProgress while the tool is still current"
            }
        } else {
            null
        }
        return ResponseEntity.ok(toolEndpoint.buildReadResponse(context, outcome))
    }
}
