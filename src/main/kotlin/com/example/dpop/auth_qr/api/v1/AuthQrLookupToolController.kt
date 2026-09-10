package com.example.dpop.auth_qr.api.v1

import com.example.dpop.auth_qr.internal.authqrlookup.AuthQrLookupToolHandler
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
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder

private const val AUTH_QR_LOOKUP_TOOL_ID = "auth-qr-lookup"

/**
 * toolId=auth-qr-lookup (docs/04-orchestrierung.md, lookup-based login). One controller owns
 * activation, PATCH and GET for this tool (docs/08-projektrahmen.md A11) - no generic toolId
 * dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: QR-Login", description = "Passwordless login: confirmed from an unrelated app, account unknown to WEB until then")
@SecurityRequirement(name = "dpop")
class AuthQrLookupToolController(
    private val handler: AuthQrLookupToolHandler,
    private val toolEndpoint: ToolEndpoint
) {

    @PostMapping("/orchestrator/api/v1/channels/{channelSessionId}/tools/auth-qr-lookup")
    @Operation(summary = "Activate auth-qr-lookup", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.beginActivation(channelSessionId, bindingKeyRef, AUTH_QR_LOOKUP_TOOL_ID)
        val outcome = handler.start(context.toolSessionId)
        val response = toolEndpoint.applyOutcome(context, outcome)
        val location = toolEndpoint.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-qr-lookup")
    @Operation(
        summary = "Poll for the APP side's decision",
        description = "No request body - the empty PATCH itself is the poll (docs/ideen/qr-login-ueber-app.md #5)."
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, AUTH_QR_LOOKUP_TOOL_ID)
        toolEndpoint.requireCurrentTool(context)
        val outcome = handler.patch(toolSessionId)
        return ResponseEntity.ok(toolEndpoint.applyOutcome(context, outcome))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-qr-lookup")
    @Operation(summary = "Read the current auth-qr-lookup state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, AUTH_QR_LOOKUP_TOOL_ID)
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
