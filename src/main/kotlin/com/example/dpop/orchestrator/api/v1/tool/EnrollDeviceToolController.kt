package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.auth_device.EnrollDeviceToolHandler
import com.example.dpop.orchestrator.dpop.buildRequestUrl
import com.example.dpop.tool_api.BindingKey
import com.example.dpop.tool_api.ChannelResponse
import com.example.dpop.tool_api.DeviceProofs
import com.example.dpop.tool_api.ToolEndpoint
import com.example.dpop.tool_spi.ToolOutcome
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

private const val ENROLL_DEVICE_TOOL_ID = "enroll-device"

data class DeviceProofPatchRequest(
    val deviceProof: String? = null,
    /** User-chosen display name for this device credential (docs/03-tool-architektur.md, allowsMultipleInstances) - purely a display metadatum, never signed, no security relevance. */
    val label: String? = null
)

/**
 * toolId=enroll-device (docs/03-tool-architektur.md): registers a device-bound key pair,
 * gated by a (demo-mocked) system PIN/biometric prompt, as a new loa2-capable credential. One
 * controller owns activation, PATCH and GET for this tool (docs/08-projektrahmen.md A11) - no
 * generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: enroll-device", description = "Device-key based enrollment (gerätebindung)")
@SecurityRequirement(name = "dpop")
class EnrollDeviceToolController(
    private val deviceProofs: DeviceProofs,
    private val handler: EnrollDeviceToolHandler,
    private val toolEndpoint: ToolEndpoint
) {

    @PostMapping("/orchestrator/api/v1/app/channels/{channelSessionId}/tools/enroll-device")
    @Operation(summary = "Activate enroll-device", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.beginActivation(channelSessionId, bindingKeyRef, ENROLL_DEVICE_TOOL_ID)
        val outcome = handler.start(context.toolSessionId)
        val response = toolEndpoint.applyOutcome(ENROLL_DEVICE_TOOL_ID, outcome, context)
        val location = toolEndpoint.activationLocation(uriBuilder.build().toUri(), context.toolSessionId, ENROLL_DEVICE_TOOL_ID)
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/enroll-device")
    @Operation(
        summary = "Confirm device enrollment",
        description = "Body carries a self-signed device-proof JWT (typ=device-proof+jwt) over this exact URL, produced after the user confirms the mocked PIN/biometric prompt."
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: DeviceProofPatchRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef)
        toolEndpoint.requireCurrentTool(context, ENROLL_DEVICE_TOOL_ID)

        val proof = deviceProofs.validate(request?.deviceProof, "PATCH", buildRequestUrl(httpRequest))
        val outcome = handler.patch(toolSessionId, proof.publicKey, proof.accessMeans, bindingKeyRef, request?.label)

        return ResponseEntity.ok(toolEndpoint.applyOutcome(ENROLL_DEVICE_TOOL_ID, outcome, context))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/enroll-device")
    @Operation(summary = "Read the current enroll-device state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef)
        val outcome = if (toolEndpoint.isCurrentTool(context, ENROLL_DEVICE_TOOL_ID)) {
            checkNotNull(handler.read(toolSessionId) as? ToolOutcome.InProgress) {
                "read() must return InProgress while the tool is still current"
            }
        } else {
            null
        }
        return ResponseEntity.ok(toolEndpoint.buildReadResponse(toolSessionId, ENROLL_DEVICE_TOOL_ID, context, outcome))
    }
}
