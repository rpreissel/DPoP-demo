package com.example.dpop.auth_password.api.v1

import com.example.dpop.auth_password.AuthPasswordUseDescriptor
import com.example.dpop.auth_password.internal.AuthPasswordUseToolHandler
import com.example.dpop.tool_api.AccountDirectory
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

private const val AUTH_PASSWORD_TOOL_ID = "auth-password"

data class AuthPasswordPatchRequest(
    val password: String? = null
)

/**
 * toolId=auth-password. One controller owns activation, PATCH and GET for this tool
 * (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: auth-password", description = "Password-based authentication (login/step-up)")
@SecurityRequirement(name = "dpop")
class AuthPasswordToolController(
    private val handler: AuthPasswordUseToolHandler,
    private val descriptor: AuthPasswordUseDescriptor,
    private val accountDirectory: AccountDirectory,
    private val toolEndpoint: ToolEndpoint
) {

    @PostMapping("/orchestrator/api/v1/app/channels/{channelSessionId}/tools/auth-password")
    @Operation(summary = "Activate auth-password", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.beginActivation(channelSessionId, bindingKeyRef, AUTH_PASSWORD_TOOL_ID)

        // Resolved and null-checked HERE, at the call site - the handler never sees a nullable
        // reference (docs/06-ablaeufe.md #3: only the orchestrator may reference `account`).
        val enrollmentRef = context.channelAccountId?.let { accountDirectory.activeEnrollment(it, descriptor.method) }
            ?: throw UnresolvableReferenceException("Keine aktive Password-Methode fuer diesen Account")
        val outcome = handler.start(context.toolSessionId, enrollmentRef)

        val response = toolEndpoint.applyOutcome(AUTH_PASSWORD_TOOL_ID, outcome, context)
        val location = toolEndpoint.activationLocation(uriBuilder.build().toUri(), context.toolSessionId, AUTH_PASSWORD_TOOL_ID)
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-password")
    @Operation(summary = "Confirm the password against the account's enrolled credential")
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: AuthPasswordPatchRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef)
        toolEndpoint.requireCurrentTool(context, AUTH_PASSWORD_TOOL_ID)

        val body = request ?: AuthPasswordPatchRequest()
        val outcome = handler.patch(toolSessionId, body.password)

        return ResponseEntity.ok(toolEndpoint.applyOutcome(AUTH_PASSWORD_TOOL_ID, outcome, context))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-password")
    @Operation(summary = "Read the current auth-password state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef)
        val outcome = if (toolEndpoint.isCurrentTool(context, AUTH_PASSWORD_TOOL_ID)) {
            checkNotNull(handler.read(toolSessionId) as? ToolOutcome.InProgress) {
                "read() must return InProgress while the tool is still current"
            }
        } else {
            null
        }
        return ResponseEntity.ok(toolEndpoint.buildReadResponse(toolSessionId, AUTH_PASSWORD_TOOL_ID, context, outcome))
    }
}
