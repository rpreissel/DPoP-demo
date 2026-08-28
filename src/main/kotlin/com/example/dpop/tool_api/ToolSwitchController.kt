package com.example.dpop.tool_api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * `DELETE /orchestrator/api/v1/tools/{toolSessionId}/{toolId}` - "Back"/"Switch": abandons the
 * currently activated tool. The single generic, toolId-keyed endpoint in this API; every other
 * tool operation has its own tool-specific controller. What happens next - falling back to
 * another candidate, narrowing a mandatory offer, or ending the journey - is decided by the
 * journey's current state, not by this controller.
 */
@RestController
@RequestMapping("/orchestrator/api/v1/tools/{toolSessionId}/{toolId}")
@Tag(name = "Tools", description = "Abandoning an activated tool (Back/Switch)")
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
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "STEP_UP_IN_PROGRESS", "currentAcr": "loa1"},
                      "next": {"type": "orchestrator", "context": "auth", "step": "selectMethod"},
                      "stepData": {"options": ["auth-password", "auth-device"]}
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
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, toolId)
        toolEndpoint.requireCurrentTool(context)
        return ResponseEntity.ok(toolEndpoint.abandon(context))
    }
}
