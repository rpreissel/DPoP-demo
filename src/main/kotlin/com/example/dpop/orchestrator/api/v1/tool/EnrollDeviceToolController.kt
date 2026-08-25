package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.auth_device.EnrollDeviceToolHandler
import com.example.dpop.orchestrator.api.v1.DpopBaseController
import com.example.dpop.orchestrator.api.v1.channel.ChannelResponse
import com.example.dpop.orchestrator.dpop.DeviceProofValidator
import com.example.dpop.orchestrator.dpop.DpopValidator
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.tool_spi.ToolCategory
import com.example.dpop.tool_spi.ToolOutcome
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import java.util.UUID

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
    dpopValidator: DpopValidator,
    jwkThumbprintService: JwkThumbprintService,
    private val deviceProofValidator: DeviceProofValidator,
    private val handler: EnrollDeviceToolHandler,
    private val controllerSupport: ToolControllerSupport
) : DpopBaseController(dpopValidator, jwkThumbprintService) {

    @PostMapping("/orchestrator/api/v1/app/channels/{channelSessionId}/tools/enroll-device")
    @Operation(summary = "Activate enroll-device", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.beginActivation(channelSessionId, bindingKeyRef, ENROLL_DEVICE_TOOL_ID, ToolCategory.ENROLL)
        val outcome = handler.start(context.toolSession.toolSessionId!!)
        val response = controllerSupport.applyOutcome(ENROLL_DEVICE_TOOL_ID, outcome, context)
        val location = controllerSupport.activationLocation(uriBuilder, context.toolSession.toolSessionId!!, ENROLL_DEVICE_TOOL_ID)
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/enroll-device")
    @Operation(
        summary = "Confirm device enrollment",
        description = "Body carries a self-signed device-proof JWT (typ=device-proof+jwt) over this exact URL, produced after the user confirms the mocked PIN/biometric prompt."
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        @RequestBody(required = false) request: DeviceProofPatchRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.loadContext(toolSessionId, bindingKeyRef)
        controllerSupport.requireCurrentTool(context, ENROLL_DEVICE_TOOL_ID)

        val proof = deviceProofValidator.validate(request?.deviceProof, "PATCH", buildRequestUrl(httpRequest))
        val outcome = handler.patch(toolSessionId, proof.toDevicePublicKey(), proof.accessMeans, bindingKeyRef, request?.label)

        return ResponseEntity.ok(controllerSupport.applyOutcome(ENROLL_DEVICE_TOOL_ID, outcome, context))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/enroll-device")
    @Operation(summary = "Read the current enroll-device state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.loadContext(toolSessionId, bindingKeyRef)
        val outcome = if (controllerSupport.isCurrentTool(context, ENROLL_DEVICE_TOOL_ID)) {
            checkNotNull(handler.read(toolSessionId) as? ToolOutcome.InProgress) {
                "read() must return InProgress while the tool is still current"
            }
        } else {
            null
        }
        return ResponseEntity.ok(controllerSupport.buildReadResponse(toolSessionId, ENROLL_DEVICE_TOOL_ID, context, outcome))
    }
}
