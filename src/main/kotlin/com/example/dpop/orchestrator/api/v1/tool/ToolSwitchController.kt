package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.tool_api.BindingKey
import com.example.dpop.tool_api.ChannelResponse
import com.example.dpop.tool_api.ToolEndpoint

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import com.example.dpop.tool_api.API_V1

/**
 * `DELETE /orchestrator/api/v1/tools/{toolSessionId}/{toolId}` - "Back"/"Switch": abandons the
 * currently activated tool. The single generic, toolId-keyed endpoint in this API; every other
 * tool operation (activation, GET, PATCH) has its own controller in its own method module.
 *
 * It lives in the orchestrator, not in `tool_api`, although it is the one endpoint no single
 * method module owns. `tool_api` is the SPI between the orchestrator and those modules - a
 * contract, not a web layer - and every method module depends on it. Serving HTTP from there gave
 * all of them a controller they neither need nor should carry.
 *
 * The orchestrator is the right owner for the other half of the same reason: what happens after an
 * abandon - falling back to another candidate, narrowing a mandatory offer, or ending the journey -
 * is decided by the journey's current state, which is the orchestrator's own.
 */
@RestController
@RequestMapping("$API_V1/tools/{toolSessionId}/{toolId}")
@Tag(name = "Tools", description = "Leaving an activated tool: back to the selection, or declining it")
@SecurityRequirement(name = "dpop")
class ToolSwitchController(private val toolEndpoint: ToolEndpoint) {

    @DeleteMapping
    @Operation(
        summary = "Abandon this tool attempt",
        description = "Moves the journey on according to the state it is standing on - to the next fallback option, " +
            "back to the selection step, or to the end of the journey if nothing else could be offered.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "auth-sms abandoned during a fallback chain - offers the other loa2 candidates.",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "STEP_UP_IN_PROGRESS", "currentAcr": "loa1"},
                      "next": {"type": "orchestrator", "context": "auth", "step": "selectMethod"},
                      "stepData": {"kind": "select-method", "options": ["auth-password", "auth-device"]}
                    }
                """)])]
            )
        ]
    )
    fun switchAway(
        @PathVariable toolSessionId: UUID,
        @PathVariable toolId: String,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadCurrent(toolSessionId, bindingKeyRef, toolId)
        return ResponseEntity.ok(toolEndpoint.abandon(context))
    }

    @PostMapping("back")
    @Operation(
        summary = "Go back from this tool to the selection",
        description = "Ends this tool attempt without declining it: the journey shows its selection page again, " +
            "with every method still on offer - this one included, and even when it is the only one. " +
            "Where the journey has no selection page (a single preferred method), this is the same as abandoning.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Back from ident-fsc during registration - both identification methods are offered again.",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "orchestrator", "context": "registration", "step": "selectIdentificationMethod"},
                      "stepData": {"kind": "select-method", "options": ["ident-fsc", "ident-eid", "ident-nect"]}
                    }
                """)])]
            )
        ]
    )
    fun back(
        @PathVariable toolSessionId: UUID,
        @PathVariable toolId: String,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadCurrent(toolSessionId, bindingKeyRef, toolId)
        return ResponseEntity.ok(toolEndpoint.back(context))
    }
}
