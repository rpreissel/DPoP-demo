package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.account.AccountService
import com.example.dpop.auth_device.AuthDeviceToolHandler
import com.example.dpop.orchestrator.api.v1.DpopBaseController
import com.example.dpop.orchestrator.api.v1.channel.ChannelResponse
import com.example.dpop.orchestrator.dpop.DeviceProofValidator
import com.example.dpop.orchestrator.dpop.DpopValidator
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.UnresolvableReferenceException
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

private const val AUTH_DEVICE_TOOL_ID = "auth-device"

/**
 * toolId=auth-device (docs/03-tool-architektur.md). One controller owns activation, PATCH and
 * GET for this tool (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: auth-device", description = "Device-key based authentication (login/step-up)")
@SecurityRequirement(name = "dpop")
class AuthDeviceToolController(
    dpopValidator: DpopValidator,
    jwkThumbprintService: JwkThumbprintService,
    private val deviceProofValidator: DeviceProofValidator,
    private val handler: AuthDeviceToolHandler,
    private val accountService: AccountService,
    private val controllerSupport: ToolControllerSupport
) : DpopBaseController(dpopValidator, jwkThumbprintService) {

    @PostMapping("/orchestrator/api/v1/app/channels/{channelSessionId}/tools/auth-device")
    @Operation(summary = "Activate auth-device", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.beginActivation(channelSessionId, bindingKeyRef, AUTH_DEVICE_TOOL_ID)

        // Resolved and null-checked HERE, at the call site - the handler never sees a nullable
        // reference (docs/06-ablaeufe.md #3: only the orchestrator may reference `account`).
        val enrollmentRef = resolveEnrollmentRef(context.channel.accountId, bindingKeyRef)
            ?: throw UnresolvableReferenceException("Keine aktive Geraete-Methode fuer dieses Geraet")
        val outcome = handler.start(context.toolSession.toolSessionId!!, enrollmentRef)

        val response = controllerSupport.applyOutcome(AUTH_DEVICE_TOOL_ID, outcome, context)
        val location = controllerSupport.activationLocation(uriBuilder, context.toolSession.toolSessionId!!, AUTH_DEVICE_TOOL_ID)
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-device")
    @Operation(
        summary = "Confirm device authentication",
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
        controllerSupport.requireCurrentTool(context, AUTH_DEVICE_TOOL_ID)

        val proof = deviceProofValidator.validate(request?.deviceProof, "PATCH", buildRequestUrl(httpRequest))
        val outcome = handler.patch(toolSessionId, proof.toDevicePublicKey(), proof.accessMeans)

        return ResponseEntity.ok(controllerSupport.applyOutcome(AUTH_DEVICE_TOOL_ID, outcome, context))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-device")
    @Operation(summary = "Read the current auth-device state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.loadContext(toolSessionId, bindingKeyRef)
        val outcome = if (controllerSupport.isCurrentTool(context, AUTH_DEVICE_TOOL_ID)) {
            checkNotNull(handler.read(toolSessionId) as? ToolOutcome.InProgress) {
                "read() must return InProgress while the tool is still current"
            }
        } else {
            null
        }
        return ResponseEntity.ok(controllerSupport.buildReadResponse(toolSessionId, AUTH_DEVICE_TOOL_ID, context, outcome))
    }

    /**
     * Unlike other auth-* tools' single-active-instance lookup, `device` can have several active
     * instances at once (one per physical device) - must pick the ONE whose
     * `deviceBindingKeyRef` matches THIS channel's own binding key, never just "any active
     * device method", or a device could be offered a credential it structurally cannot use
     * (docs/04-orchestrierung.md).
     */
    private fun resolveEnrollmentRef(accountId: Long?, bindingKeyRef: String): EnrollmentRef? {
        val method = accountId
            ?.let { accountService.findActiveMethods(it, handler.method) }
            ?.firstOrNull { it.details?.get("deviceBindingKeyRef") == bindingKeyRef }
            ?: return null
        val raw = method.details?.get("enrollmentRef") as? Map<*, *> ?: return null
        val type = raw["type"] as? String ?: return null
        val id = raw["id"] as? String ?: return null
        return EnrollmentRef(type, id)
    }
}
