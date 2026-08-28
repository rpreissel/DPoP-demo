package com.example.dpop.id_eid.api.v1

import com.example.dpop.id_eid.internal.EidPatchFields
import com.example.dpop.id_eid.internal.IdentEidToolHandler
import com.example.dpop.tool_api.BindingKey
import com.example.dpop.tool_api.ChannelResponse
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_api.ToolEndpoint
import com.example.dpop.tool_spi.ToolOutcome
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.LocalDate
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

private const val IDENT_EID_TOOL_ID = "ident-eid"

data class IdentEidPatchRequest(
    @field:Schema(example = "A123456789") val kvnr: String? = null,
    @field:Schema(example = "Muster") val name: String? = null,
    @field:Schema(example = "Max") val vorname: String? = null,
    @field:Schema(example = "1985-03-12") val geburtsdatum: LocalDate? = null,
    @field:Schema(example = "Musterstraße") val strasse: String? = null,
    @field:Schema(example = "1") val hausnummer: String? = null,
    @field:Schema(example = "10117") val plz: String? = null,
    @field:Schema(example = "Berlin") val ort: String? = null,
    @field:Schema(example = "123456") val pin: String? = null
)

/**
 * toolId=ident-eid. One controller owns activation, PATCH and GET for this tool
 * (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: eID", description = "KVNR/name/vorname lookup, simulated eID card read, PIN")
@SecurityRequirement(name = "dpop")
class IdentEidToolController(
    private val handler: IdentEidToolHandler,
    private val personDirectory: PersonDirectory,
    private val toolEndpoint: ToolEndpoint
) {

    @PostMapping("/orchestrator/api/v1/channels/{channelSessionId}/tools/ident-eid")
    @Operation(
        summary = "Activate ident-eid",
        description = "No request body: toolId already carries kind and method.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "ident-eid", "step": "input", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
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
        val context = toolEndpoint.beginActivation(channelSessionId, bindingKeyRef, IDENT_EID_TOOL_ID)
        val outcome = handler.start(context.toolSessionId)
        val response = toolEndpoint.applyOutcome(context, outcome)
        val location = toolEndpoint.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/ident-eid")
    @Operation(
        summary = "Supply KVNR/name/vorname, the simulated card's Ausweisdaten, and the PIN",
        description = "Only the fields for the current step need to be sent; all of them together also resolves in one call.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(examples = [
                    ExampleObject(name = "After kvnr/name/vorname - card step", value = """
                        {
                          "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                          "next": {"type": "tool", "toolId": "ident-eid", "step": "card", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
                        }
                    """),
                    ExampleObject(name = "After card data - pin step", value = """
                        {
                          "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                          "next": {"type": "tool", "toolId": "ident-eid", "step": "pin", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
                        }
                    """),
                    ExampleObject(name = "After pin - identified, chain continues", value = """
                        {
                          "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                          "next": {"type": "orchestrator", "context": "enrollment", "step": "selectMethod"},
                          "stepData": {"options": ["enroll-sms", "enroll-device"]}
                        }
                    """)
                ])]
            )
        ]
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: IdentEidPatchRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, IDENT_EID_TOOL_ID)
        toolEndpoint.requireCurrentTool(context)

        val body = request ?: IdentEidPatchRequest()
        val personId = body.kvnr?.let { personDirectory.findPersonIdByKvnr(it) }
        val fields = EidPatchFields(
            kvnr = body.kvnr,
            name = body.name,
            vorname = body.vorname,
            geburtsdatum = body.geburtsdatum,
            strasse = body.strasse,
            hausnummer = body.hausnummer,
            plz = body.plz,
            ort = body.ort,
            pin = body.pin
        )
        // Folded into the handler's ordinary failure rather than raised - see
        // ToolEndpoint.isIdentLockedOut: a distinguishable lock would leak which KVNRs exist.
        val outcome = handler.patch(toolSessionId, fields, personId, toolEndpoint.isIdentLockedOut(personId))

        return ResponseEntity.ok(toolEndpoint.applyOutcome(context, outcome))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/ident-eid")
    @Operation(
        summary = "Read the current ident-eid state",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "ident-eid", "step": "card", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
                    }
                """)])]
            )
        ]
    )
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, IDENT_EID_TOOL_ID)
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
