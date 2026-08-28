package com.example.dpop.auth_email.api.v1

import com.example.dpop.auth_email.internal.EnrollEmailToolHandler
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
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder

private const val ENROLL_EMAIL_TOOL_ID = "enroll-email"

data class EnrollEmailPatchRequest(
    @field:Schema(example = "max.mustermann@example.com") val email: String? = null,
    @field:Schema(example = "123456") val code: String? = null
)

/**
 * toolId=enroll-email. One controller owns activation, PATCH and GET for this tool
 * (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: E-Mail", description = "Registers a confirmed email address as a knowledge/possession factor")
@SecurityRequirement(name = "dpop")
class EnrollEmailToolController(
    private val handler: EnrollEmailToolHandler,
    private val toolEndpoint: ToolEndpoint
) {

    @PostMapping("/orchestrator/api/v1/app/channels/{channelSessionId}/tools/enroll-email")
    @Operation(
        summary = "Activate enroll-email",
        description = "No request body: toolId already carries kind and method.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "enroll-email", "step": "enroll", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
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

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/enroll-email")
    @Operation(
        summary = "Supply email, then the confirmation code",
        description = "First call with email triggers the code send; a second call with code confirms it.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(examples = [
                    ExampleObject(name = "After email - code sent", value = """
                        {
                          "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                          "next": {"type": "tool", "toolId": "enroll-email", "step": "codeInput", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"},
                          "demo": {"tan": "123456"}
                        }
                    """),
                    ExampleObject(name = "After code - confirmed, chain continues", value = """
                        {
                          "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                          "next": {"type": "orchestrator", "context": "enrollment", "step": "selectMethod"},
                          "stepData": {"options": ["enroll-password"]}
                        }
                    """)
                ])]
            )
        ]
    )
    // The only tool PATCH that spans a transaction: the handler writes the confirmed address onto
    // Account, and applyOutcome then records the authentication method. Without this bracket the
    // two commit separately, and a failure in between would leave a confirmed email on an account
    // that has no email method to show for it.
    @Transactional
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: EnrollEmailPatchRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, ENROLL_EMAIL_TOOL_ID)
        toolEndpoint.requireCurrentTool(context)

        val body = request ?: EnrollEmailPatchRequest()
        val accountId = checkNotNull(context.journeyAccountId) { "enroll-email without an account bound to the journey" }
        val outcome = handler.patch(toolSessionId, body.email, body.code, accountId)

        return ResponseEntity.ok(toolEndpoint.applyOutcome(context, outcome))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/enroll-email")
    @Operation(
        summary = "Read the current enroll-email state",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "enroll-email", "step": "codeInput", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
                    }
                """)])]
            )
        ]
    )
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, ENROLL_EMAIL_TOOL_ID)
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
