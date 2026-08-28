package com.example.dpop.auth_password.api.v1

import com.example.dpop.auth_password.AuthPasswordLookupDescriptor
import com.example.dpop.auth_password.internal.AuthPasswordLookupToolHandler
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

private const val AUTH_PASSWORD_LOOKUP_TOOL_ID = "auth-password-lookup"

data class AuthPasswordLookupPatchRequest(
    @field:Schema(example = "max.mustermann@example.com") val email: String? = null,
    @field:Schema(example = "Passwort!23") val password: String? = null
)

/**
 * toolId=auth-password-lookup (docs/04-orchestrierung.md, lookup-based login). One controller
 * owns activation, PATCH and GET for this tool (docs/08-projektrahmen.md A11) - no generic
 * toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: Passwort", description = "Login ohne DPoP via email + password")
@SecurityRequirement(name = "dpop")
class AuthPasswordLookupToolController(
    private val handler: AuthPasswordLookupToolHandler,
    private val descriptor: AuthPasswordLookupDescriptor,
    private val accountDirectory: AccountDirectory,
    private val toolEndpoint: ToolEndpoint
) {

    @PostMapping("/orchestrator/api/v1/channels/{channelSessionId}/tools/auth-password-lookup")
    @Operation(
        summary = "Activate auth-password-lookup",
        description = "No request body: toolId already carries kind and method.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "auth-password-lookup", "step": "auth", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
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
        val context = toolEndpoint.beginActivation(channelSessionId, bindingKeyRef, AUTH_PASSWORD_LOOKUP_TOOL_ID)
        val outcome = handler.start(context.toolSessionId)
        val response = toolEndpoint.applyOutcome(context, outcome)
        val location = toolEndpoint.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-password-lookup")
    @Operation(
        summary = "Supply email and password together (self-verifying, single call)",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Correct email+password - logged in, offered the optional device-binding.",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED", "currentAcr": "loa1", "currentAmr": ["password"]},
                      "next": {"type": "orchestrator", "context": "authentication", "step": "offerDeviceBinding"}
                    }
                """)])]
            )
        ]
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: AuthPasswordLookupPatchRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, AUTH_PASSWORD_LOOKUP_TOOL_ID)
        toolEndpoint.requireCurrentTool(context)

        val body = request ?: AuthPasswordLookupPatchRequest()
        // Resolved HERE, at the call site - auth_password may not depend on `account`
        // (docs/08-projektrahmen.md A11). Both null when the email is unknown or has no active
        // password method; the handler treats that identically to a wrong password.
        val resolved = body.email?.let { accountDirectory.resolveAccountByEmail(it) }
        // A throttled account is dropped to null and thereby handled exactly like an unknown
        // address - never as its own error. Surfacing the lock (423) here would tell an attacker
        // which addresses have accounts, undoing the constant-shape failure this tool is built
        // around (see ToolEndpoint.isLockedOut).
        val accountId = resolved.takeUnless { toolEndpoint.isLockedOut(it) }
        val enrollmentRef = accountId?.let { accountDirectory.activeEnrollment(it, descriptor.method) }
        val outcome = handler.patch(toolSessionId, body.email, body.password, accountId, enrollmentRef)

        return ResponseEntity.ok(toolEndpoint.applyOutcome(context, outcome))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-password-lookup")
    @Operation(
        summary = "Read the current auth-password-lookup state",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "auth-password-lookup", "step": "auth", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
                    }
                """)])]
            )
        ]
    )
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, AUTH_PASSWORD_LOOKUP_TOOL_ID)
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
