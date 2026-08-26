package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.auth_email.AuthEmailLookupToolHandler
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

private const val AUTH_EMAIL_LOOKUP_TOOL_ID = "auth-email-lookup"

data class AuthEmailLookupPatchRequest(val email: String? = null, val code: String? = null)

/**
 * toolId=auth-email-lookup (docs/04-orchestrierung.md, lookup-based login). One controller owns
 * activation, PATCH and GET for this tool (docs/08-projektrahmen.md A11) - no generic toolId
 * dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: auth-email-lookup", description = "Login ohne DPoP via bestaetigte E-Mail-Adresse + Code")
@SecurityRequirement(name = "dpop")
class AuthEmailLookupToolController(
    private val handler: AuthEmailLookupToolHandler,
    private val toolEndpoint: ToolEndpoint
) {

    @PostMapping("/orchestrator/api/v1/app/channels/{channelSessionId}/tools/auth-email-lookup")
    @Operation(summary = "Activate auth-email-lookup", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.beginActivation(channelSessionId, bindingKeyRef, AUTH_EMAIL_LOOKUP_TOOL_ID)
        val outcome = handler.start(context.toolSessionId)
        val response = toolEndpoint.applyOutcome(AUTH_EMAIL_LOOKUP_TOOL_ID, outcome, context)
        val location = toolEndpoint.activationLocation(uriBuilder.build().toUri(), context.toolSessionId, AUTH_EMAIL_LOOKUP_TOOL_ID)
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-email-lookup")
    @Operation(
        summary = "Supply email, then code",
        description = "First call with email resolves the account and triggers the confirmation code send; a second call with code confirms it."
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: AuthEmailLookupPatchRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef)
        toolEndpoint.requireCurrentTool(context, AUTH_EMAIL_LOOKUP_TOOL_ID)

        val body = request ?: AuthEmailLookupPatchRequest()
        val outcome = if (body.code != null) {
            handler.patch(toolSessionId, body.code)
        } else if (body.email != null) {
            handler.submitEmail(toolSessionId, body.email)
        } else {
            handler.patch(toolSessionId, null)
        }

        return ResponseEntity.ok(toolEndpoint.applyOutcome(AUTH_EMAIL_LOOKUP_TOOL_ID, outcome, context))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-email-lookup")
    @Operation(summary = "Read the current auth-email-lookup state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef)
        val outcome = if (toolEndpoint.isCurrentTool(context, AUTH_EMAIL_LOOKUP_TOOL_ID)) {
            checkNotNull(handler.read(toolSessionId) as? ToolOutcome.InProgress) {
                "read() must return InProgress while the tool is still current"
            }
        } else {
            null
        }
        return ResponseEntity.ok(toolEndpoint.buildReadResponse(toolSessionId, AUTH_EMAIL_LOOKUP_TOOL_ID, context, outcome))
    }
}
