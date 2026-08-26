package com.example.dpop.auth_email.api.v1

import com.example.dpop.auth_email.internal.AuthEmailUseToolHandler
import com.example.dpop.tool_api.BindingKey
import com.example.dpop.tool_api.ChannelResponse
import com.example.dpop.tool_api.ToolEndpoint
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.UnresolvableReferenceException
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

private const val AUTH_EMAIL_TOOL_ID = "auth-email"

data class AuthEmailPatchRequest(val code: String? = null)

/**
 * toolId=auth-email (device-linked case). One controller owns activation, PATCH and GET for
 * this tool (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: auth-email", description = "Email-based authentication (login/step-up)")
@SecurityRequirement(name = "dpop")
class AuthEmailToolController(
    private val handler: AuthEmailUseToolHandler,
    private val toolEndpoint: ToolEndpoint
) {

    @PostMapping("/orchestrator/api/v1/app/channels/{channelSessionId}/tools/auth-email")
    @Operation(summary = "Activate auth-email", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.beginActivation(channelSessionId, bindingKeyRef, AUTH_EMAIL_TOOL_ID)

        // Only the accountId is resolved here, so the handler never sees a nullable parameter
        // (docs/03-tool-architektur.md #2); the confirmed address itself is the handler's own
        // lookup, same 422 either way.
        val accountId = context.channelAccountId
            ?: throw UnresolvableReferenceException("Kein Konto fuer diesen Kanal")
        val outcome = handler.start(context.toolSessionId, accountId)

        val response = toolEndpoint.applyOutcome(AUTH_EMAIL_TOOL_ID, outcome, context)
        val location = toolEndpoint.activationLocation(uriBuilder.build().toUri(), context.toolSessionId, AUTH_EMAIL_TOOL_ID)
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-email")
    @Operation(summary = "Confirm the code sent to the account's confirmed email address")
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: AuthEmailPatchRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef)
        toolEndpoint.requireCurrentTool(context, AUTH_EMAIL_TOOL_ID)

        val body = request ?: AuthEmailPatchRequest()
        val outcome = handler.patch(toolSessionId, body.code)

        return ResponseEntity.ok(toolEndpoint.applyOutcome(AUTH_EMAIL_TOOL_ID, outcome, context))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-email")
    @Operation(summary = "Read the current auth-email state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef)
        val outcome = if (toolEndpoint.isCurrentTool(context, AUTH_EMAIL_TOOL_ID)) {
            checkNotNull(handler.read(toolSessionId) as? ToolOutcome.InProgress) {
                "read() must return InProgress while the tool is still current"
            }
        } else {
            null
        }
        return ResponseEntity.ok(toolEndpoint.buildReadResponse(toolSessionId, AUTH_EMAIL_TOOL_ID, context, outcome))
    }
}
