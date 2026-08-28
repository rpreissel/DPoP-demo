package com.example.dpop.auth_email.api.v1

import com.example.dpop.auth_email.internal.AuthEmailLookupToolHandler
import com.example.dpop.tool_api.AccountDirectory
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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder

private const val AUTH_EMAIL_LOOKUP_TOOL_ID = "auth-email-lookup"

data class AuthEmailLookupPatchRequest(
    @field:Schema(example = "max.mustermann@example.com") val email: String? = null,
    @field:Schema(example = "123456") val code: String? = null
)

/**
 * toolId=auth-email-lookup (docs/04-orchestrierung.md, lookup-based login). One controller owns
 * activation, PATCH and GET for this tool (docs/08-projektrahmen.md A11) - no generic toolId
 * dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: E-Mail", description = "Login ohne DPoP via bestaetigte E-Mail-Adresse + Code")
@SecurityRequirement(name = "dpop")
class AuthEmailLookupToolController(
    private val handler: AuthEmailLookupToolHandler,
    private val accountDirectory: AccountDirectory,
    private val toolEndpoint: ToolEndpoint
) {

    @PostMapping("/orchestrator/api/v1/channels/{channelSessionId}/tools/auth-email-lookup")
    @Operation(
        summary = "Activate auth-email-lookup",
        description = "No request body: toolId already carries kind and method.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "auth-email-lookup", "step": "auth", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
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
        val context = toolEndpoint.beginActivation(channelSessionId, bindingKeyRef, AUTH_EMAIL_LOOKUP_TOOL_ID)
        val outcome = handler.start(context.toolSessionId)
        val response = toolEndpoint.applyOutcome(context, outcome)
        val location = toolEndpoint.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-email-lookup")
    @Operation(
        summary = "Supply email, then code",
        description = "First call with email resolves the account and triggers the confirmation code send; a second call with code confirms it.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(examples = [
                    ExampleObject(name = "After email - code sent", value = """
                        {
                          "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                          "next": {"type": "tool", "toolId": "auth-email-lookup", "step": "codeInput", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"},
                          "demo": {"tan": "123456"}
                        }
                    """),
                    ExampleObject(name = "After code - logged in, device-binding offer", value = """
                        {
                          "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED", "currentAcr": "loa1", "currentAmr": ["email"]},
                          "next": {"type": "orchestrator", "context": "authentication", "step": "offerDeviceBinding"}
                        }
                    """)
                ])]
            )
        ]
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: AuthEmailLookupPatchRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, AUTH_EMAIL_LOOKUP_TOOL_ID)
        toolEndpoint.requireCurrentTool(context)

        val body = request ?: AuthEmailLookupPatchRequest()
        val outcome = if (body.code != null) {
            handler.patch(toolSessionId, body.code)
        } else if (body.email != null) {
            // Resolved here only to key the throttle - the handler still owns the e-mail
            // semantics (confirmed vs. merely known) via its declared `auth_email -> account`
            // dependency. A locked account is passed as `throttled` rather than raised as an
            // error, so the response stays indistinguishable from an unknown address.
            val throttled = toolEndpoint.isLockedOut(accountDirectory.resolveAccountByEmail(body.email))
            handler.submitEmail(toolSessionId, body.email, throttled)
        } else {
            handler.patch(toolSessionId, null)
        }

        return ResponseEntity.ok(toolEndpoint.applyOutcome(context, outcome))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-email-lookup")
    @Operation(
        summary = "Read the current auth-email-lookup state",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "auth-email-lookup", "step": "codeInput", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
                    }
                """)])]
            )
        ]
    )
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, AUTH_EMAIL_LOOKUP_TOOL_ID)
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
