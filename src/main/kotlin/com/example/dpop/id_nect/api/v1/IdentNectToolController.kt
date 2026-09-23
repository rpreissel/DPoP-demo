package com.example.dpop.id_nect.api.v1

import com.example.dpop.id_nect.internal.IdentNectToolHandler
import com.example.dpop.tool_api.API_V1
import com.example.dpop.tool_api.BindingKey
import com.example.dpop.tool_api.ChannelResponse
import com.example.dpop.tool_api.ToolEndpoint
import com.example.dpop.tool_spi.ToolOutcome
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import java.util.UUID

private const val IDENT_NECT_TOOL_ID = "ident-nect"

data class IdentNectPatchRequest(
    @field:Schema(description = "The case id Nect sent the user back with (?nectCaseId=...).")
    val caseId: UUID? = null,
    @field:Schema(description = "true opens a fresh Nect case instead of reporting one.")
    val retry: Boolean? = null
)

/**
 * toolId=ident-nect. One controller owns activation, PATCH and GET for this tool
 * (docs/08-projektrahmen.md A11). The client only ever carries the case id; the identification
 * result goes from Nect to the backend directly.
 */
@RestController
@Tag(name = "Tool: Nect", description = "Identification at Nect (eID, passport or EUDI wallet) via jump page and return")
@SecurityRequirement(name = "dpop")
class IdentNectToolController(
    private val handler: IdentNectToolHandler,
    private val toolEndpoint: ToolEndpoint
) {

    @PostMapping("$API_V1/channels/{channelSessionId}/tools/ident-nect")
    @Operation(
        summary = "Activate ident-nect",
        description = "No request body. Opens a Nect case; stepData carries the jump URL.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "ident-nect", "step": "redirect", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"},
                      "stepData": {"kind": "nect-redirect", "jumpUrl": "/nect/?case=5b1c2d3e-0000-4000-8000-000000000001", "caseId": "5b1c2d3e-0000-4000-8000-000000000001"}
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
        val context = toolEndpoint.beginActivation(channelSessionId, bindingKeyRef, IDENT_NECT_TOOL_ID)
        val outcome = handler.start(context.toolSessionId)
        val response = toolEndpoint.applyOutcome(context, outcome)
        val location = toolEndpoint.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("$API_V1/tools/{toolSessionId}/ident-nect")
    @Operation(
        summary = "Report the returned Nect case, or open a new one",
        description = "The backend redeems the case's result from Nect itself - once, and only for the case this tool session opened.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(name = "Attested, the assignment question follows", value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "orchestrator", "context": "prompt", "step": "confirm"},
                      "stepData": {"kind": "confirm", "prompt": {"kind": "Confirm", "title": "Konto Ihrer Versichertennummer zuordnen?"}}
                    }
                """)])]
            )
        ]
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: IdentNectPatchRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadCurrent(toolSessionId, bindingKeyRef, IDENT_NECT_TOOL_ID)
        val body = request ?: IdentNectPatchRequest()
        val outcome = handler.patch(toolSessionId, body.caseId, body.retry == true)
        return ResponseEntity.ok(toolEndpoint.applyOutcome(context, outcome))
    }

    @GetMapping("$API_V1/tools/{toolSessionId}/ident-nect")
    @Operation(summary = "Read the current ident-nect state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, IDENT_NECT_TOOL_ID)
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
