package com.example.dpop.auth_qr.api.v1

import com.example.dpop.texts.Text
import com.example.dpop.auth_qr.AuthQrDescriptor
import com.example.dpop.auth_qr.internal.authqr.AuthQrToolHandler
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
import com.example.dpop.tool_api.API_V1

private const val AUTH_QR_TOOL_ID = "auth-qr"

/**
 * toolId=auth-qr. One controller owns activation, PATCH and GET for this tool
 * (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: QR-Login")
@SecurityRequirement(name = "dpop")
class AuthQrToolController(
    private val handler: AuthQrToolHandler,
    private val descriptor: AuthQrDescriptor,
    private val accountDirectory: AccountDirectory,
    private val toolEndpoint: ToolEndpoint
) {

    @PostMapping("$API_V1/channels/{channelSessionId}/tools/auth-qr")
    @Operation(summary = "Activate auth-qr", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.beginActivation(channelSessionId, bindingKeyRef, AUTH_QR_TOOL_ID)
        val accountId = context.accountId
            ?.takeIf { accountDirectory.activeEnrollment(it, descriptor.method) != null }
            ?: throw UnresolvableReferenceException(Text("Kein aktives Anmeldeverfahren dieser Art fuer dieses Konto"), "no active qr-login method")
        val outcome = handler.start(context.toolSessionId, accountId)

        val response = toolEndpoint.applyOutcome(context, outcome)
        val location = toolEndpoint.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("$API_V1/tools/{toolSessionId}/auth-qr")
    @Operation(
        summary = "Poll for the APP side's decision, then submit the confirmation code the app shows",
        description = "Step waitForApp: an empty PATCH is the poll. Step enterCode: the app approved and shows a " +
            "confirmation code; the browser is logged in only once it submits that code (docs/05-api.md, " +
            "Peer-Login bestätigen; docs/07-betrieb.md #5)."
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: QrConfirmationCodeRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadCurrent(toolSessionId, bindingKeyRef, AUTH_QR_TOOL_ID)
        val outcome = handler.patch(toolSessionId, request?.confirmationCode)
        return ResponseEntity.ok(toolEndpoint.applyOutcome(context, outcome))
    }

    @GetMapping("$API_V1/tools/{toolSessionId}/auth-qr")
    @Operation(summary = "Read the current auth-qr state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, AUTH_QR_TOOL_ID)
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
