package com.example.dpop.auth_email.api.v1

import com.example.dpop.auth_email.internal.enrollemail.EnrollEmailToolHandler
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
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import com.example.dpop.tool_api.API_V1

private const val ENROLL_EMAIL_TOOL_ID = "enroll-email"

/**
 * toolId=enroll-email. One controller per tool (docs/08-projektrahmen.md A11), but this one has no
 * PATCH: activating the tool completes it, because the address was already proven by
 * `confirm-email` and there is nothing for the client to submit.
 */
@RestController
@Tag(name = "Tool: E-Mail-Login", description = "Activates the account's confirmed address as an authentication method")
@SecurityRequirement(name = "dpop")
class EnrollEmailToolController(
    private val handler: EnrollEmailToolHandler,
    private val toolEndpoint: ToolEndpoint
) {

    @PostMapping("$API_V1/channels/{channelSessionId}/tools/enroll-email")
    @Operation(
        summary = "Activate enroll-email",
        description = "One shot: no request body, and the response already carries the completed outcome.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED"},
                      "next": {"type": "orchestrator", "context": "authentication", "step": "done"}
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
        val context = toolEndpoint.beginActivation(channelSessionId, bindingKeyRef, ENROLL_EMAIL_TOOL_ID)
        val outcome = handler.start(context.toolSessionId)
        val response = toolEndpoint.applyOutcome(context, outcome)
        val location = toolEndpoint.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @GetMapping("$API_V1/tools/{toolSessionId}/enroll-email")
    @Operation(summary = "Read the current enroll-email state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, ENROLL_EMAIL_TOOL_ID)
        // Never InProgress - this tool has a single, already-completed state, so the read path
        // only ever reports what the journey has moved on to.
        return ResponseEntity.ok(toolEndpoint.buildReadResponse(context, null))
    }
}
