package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.auth_device.AuthDeviceToolHandler
import com.example.dpop.orchestrator.dpop.buildRequestUrl
import com.example.dpop.tool_api.AccountDirectory
import com.example.dpop.tool_api.BindingKey
import com.example.dpop.tool_api.ChannelResponse
import com.example.dpop.tool_api.DeviceProofs
import com.example.dpop.tool_api.ToolEndpoint
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.UnresolvableReferenceException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
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

private const val AUTH_DEVICE_TOOL_ID = "auth-device"

/**
 * toolId=auth-device (docs/03-tool-architektur.md). One controller owns activation, PATCH and
 * GET for this tool (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: auth-device", description = "Device-key based authentication (login/step-up)")
@SecurityRequirement(name = "dpop")
class AuthDeviceToolController(
    private val deviceProofs: DeviceProofs,
    private val handler: AuthDeviceToolHandler,
    private val accountDirectory: AccountDirectory,
    private val toolEndpoint: ToolEndpoint
) {

    @PostMapping("/orchestrator/api/v1/app/channels/{channelSessionId}/tools/auth-device")
    @Operation(summary = "Activate auth-device", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.beginActivation(channelSessionId, bindingKeyRef, AUTH_DEVICE_TOOL_ID)

        // Resolved and null-checked HERE, at the call site - the handler never sees a nullable
        // reference (docs/06-ablaeufe.md #3: only the orchestrator may reference `account`).
        val enrollmentRef = context.channelAccountId
            ?.let { accountDirectory.activeDeviceEnrollment(it, handler.method, bindingKeyRef) }
            ?: throw UnresolvableReferenceException("Keine aktive Geraete-Methode fuer dieses Geraet")
        val outcome = handler.start(context.toolSessionId, enrollmentRef)

        val response = toolEndpoint.applyOutcome(AUTH_DEVICE_TOOL_ID, outcome, context)
        val location = toolEndpoint.activationLocation(uriBuilder.build().toUri(), context.toolSessionId, AUTH_DEVICE_TOOL_ID)
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-device")
    @Operation(
        summary = "Confirm device authentication",
        description = "Body carries a self-signed device-proof JWT (typ=device-proof+jwt) over this exact URL, produced after the user confirms the mocked PIN/biometric prompt."
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: DeviceProofPatchRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef)
        toolEndpoint.requireCurrentTool(context, AUTH_DEVICE_TOOL_ID)

        val proof = deviceProofs.validate(request?.deviceProof, "PATCH", buildRequestUrl(httpRequest))
        val outcome = handler.patch(toolSessionId, proof.publicKey, proof.accessMeans)

        return ResponseEntity.ok(toolEndpoint.applyOutcome(AUTH_DEVICE_TOOL_ID, outcome, context))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-device")
    @Operation(summary = "Read the current auth-device state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef)
        val outcome = if (toolEndpoint.isCurrentTool(context, AUTH_DEVICE_TOOL_ID)) {
            checkNotNull(handler.read(toolSessionId) as? ToolOutcome.InProgress) {
                "read() must return InProgress while the tool is still current"
            }
        } else {
            null
        }
        return ResponseEntity.ok(toolEndpoint.buildReadResponse(toolSessionId, AUTH_DEVICE_TOOL_ID, context, outcome))
    }
}
