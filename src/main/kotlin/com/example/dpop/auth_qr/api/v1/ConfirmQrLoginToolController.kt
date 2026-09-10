package com.example.dpop.auth_qr.api.v1

import com.example.dpop.auth_qr.ConfirmQrLoginDescriptor
import com.example.dpop.auth_qr.internal.confirmqrlogin.ConfirmQrLoginToolHandler
import com.example.dpop.tool_api.AccountDirectory
import com.example.dpop.tool_api.BindingKey
import com.example.dpop.tool_api.ChannelResponse
import com.example.dpop.tool_api.ToolEndpoint
import com.example.dpop.tool_spi.ToolOutcome
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
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

private const val CONFIRM_QR_LOGIN_TOOL_ID = "confirm-qr-login"

data class ConfirmQrLoginPatchRequest(
    @field:Schema(example = "ABCD-1234") val pairingCode: String? = null,
    @field:Schema(example = "accept") val decision: String? = null
)

/**
 * toolId=confirm-qr-login. One controller owns activation, PATCH and GET for this tool
 * (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: QR-Login", description = "Approve or decline a WEB channel's pairing from this already-authenticated app")
@SecurityRequirement(name = "dpop")
class ConfirmQrLoginToolController(
    private val handler: ConfirmQrLoginToolHandler,
    private val descriptor: ConfirmQrLoginDescriptor,
    private val accountDirectory: AccountDirectory,
    private val toolEndpoint: ToolEndpoint
) {

    @PostMapping("/orchestrator/api/v1/channels/{channelSessionId}/tools/confirm-qr-login")
    @Operation(summary = "Activate confirm-qr-login", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.beginActivation(channelSessionId, bindingKeyRef, CONFIRM_QR_LOGIN_TOOL_ID)
        val outcome = handler.start(context.toolSessionId)
        val response = toolEndpoint.applyOutcome(context, outcome)
        val location = toolEndpoint.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/confirm-qr-login")
    @Operation(
        summary = "Supply the pairing code, then the accept/reject decision",
        description = "First call: {pairingCode}. Once resolved: {decision: accept|reject}."
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: ConfirmQrLoginPatchRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, CONFIRM_QR_LOGIN_TOOL_ID)
        toolEndpoint.requireCurrentTool(context)

        val body = request ?: ConfirmQrLoginPatchRequest()
        val accountId = checkNotNull(context.channelAccountId) { "confirm-qr-login on a channel without an accountId" }
        // Resolved HERE, at the call site - this module may not depend on `account`
        // (docs/03-tool-architektur.md #2). An account without an active enroll-qr opt-in must
        // never approve on its own behalf, checked only when actually accepting.
        val hasQrEnrollment = accountDirectory.activeEnrollment(accountId, descriptor.method) != null
        val outcome = handler.patch(toolSessionId, body.pairingCode, body.decision, accountId, hasQrEnrollment)

        return ResponseEntity.ok(toolEndpoint.applyOutcome(context, outcome))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/confirm-qr-login")
    @Operation(summary = "Read the current confirm-qr-login state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, CONFIRM_QR_LOGIN_TOOL_ID)
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
