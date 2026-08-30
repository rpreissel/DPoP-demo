package com.example.dpop.auth_device.api.v1

import com.example.dpop.auth_device.AuthDeviceDescriptor
import com.example.dpop.auth_device.internal.AuthDeviceToolHandler
import com.example.dpop.tool_api.buildRequestUrl
import com.example.dpop.tool_api.AccountDirectory
import com.example.dpop.tool_api.BindingKey
import com.example.dpop.tool_api.ChannelResponse
import com.example.dpop.tool_api.DeviceProofs
import com.example.dpop.tool_api.ToolEndpoint
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.UnresolvableReferenceException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
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
@Tag(name = "Tool: Gerät", description = "Device-key based authentication (login/step-up)")
@SecurityRequirement(name = "dpop")
class AuthDeviceToolController(
    private val deviceProofs: DeviceProofs,
    private val handler: AuthDeviceToolHandler,
    private val descriptor: AuthDeviceDescriptor,
    private val accountDirectory: AccountDirectory,
    private val toolEndpoint: ToolEndpoint
) {

    @PostMapping("/orchestrator/api/v1/channels/{channelSessionId}/tools/auth-device")
    @Operation(
        summary = "Activate auth-device",
        description = "No request body: toolId already carries kind and method.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "ANONYMOUS"},
                      "next": {"type": "tool", "toolId": "auth-device", "step": "auth", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
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
        val context = toolEndpoint.beginActivation(channelSessionId, bindingKeyRef, AUTH_DEVICE_TOOL_ID)

        // Resolved and null-checked HERE, at the call site - the handler never sees a nullable
        // reference (docs/06-ablaeufe.md #3: only the orchestrator may reference `account`).
        val enrollmentRef = context.channelAccountId
            ?.let { accountDirectory.activeInstanceEnrollment(it, descriptor.method) { details -> descriptor.matchesCaller(details, bindingKeyRef) } }
            ?: throw UnresolvableReferenceException("Keine aktive Geraete-Methode fuer dieses Geraet")
        val outcome = handler.start(context.toolSessionId, enrollmentRef)

        val response = toolEndpoint.applyOutcome(context, outcome)
        val location = toolEndpoint.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-device")
    @Operation(
        summary = "Confirm device authentication",
        description = "Body carries a self-signed device-proof JWT (typ=device-proof+jwt) over this exact URL, produced after the user confirms the mocked PIN/biometric prompt.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED", "currentAcr": "loa2", "currentAmr": ["device"]},
                      "next": {"type": "orchestrator", "context": "authentication", "step": "authenticated"}
                    }
                """)])]
            )
        ]
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: DeviceProofPatchRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, AUTH_DEVICE_TOOL_ID)
        toolEndpoint.requireCurrentTool(context)

        val proof = deviceProofs.validate(request?.deviceProof, "PATCH", buildRequestUrl(httpRequest))
        val outcome = handler.patch(toolSessionId, proof.publicKey, proof.userVerification)

        return ResponseEntity.ok(toolEndpoint.applyOutcome(context, outcome))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-device")
    @Operation(
        summary = "Read the current auth-device state",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "ANONYMOUS"},
                      "next": {"type": "tool", "toolId": "auth-device", "step": "auth", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
                    }
                """)])]
            )
        ]
    )
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, AUTH_DEVICE_TOOL_ID)
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
