package com.example.dpop.auth_kobil.api.v1

import com.example.dpop.auth_kobil.internal.enrollkobil.EnrollKobilToolHandler
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

private const val ENROLL_KOBIL_TOOL_ID = "enroll-kobil"

data class EnrollKobilPatchRequest(
    /** The app confirming that the SDK's activation ran; the device identifier is asked of KOBIL, never of the client. */
    @field:Schema(example = "true")
    val activated: Boolean? = null,
    /**
     * Whether the user agreed to unlocking this credential by biometrics. Only then does the
     * server keep a counterpart to the app's local secret at all; without it the credential has
     * exactly one way in, the account password it already depends on. Required - there is no
     * default for a consent.
     */
    @field:Schema(example = "true")
    val biometricConsent: Boolean? = null,
    /** User-chosen display name for this credential - display metadata only, no security relevance. */
    @field:Schema(example = "Diensthandy")
    val label: String? = null,
)

/**
 * toolId=enroll-kobil: binds a smartphone through KOBIL and keeps the resulting device
 * identifier, the backend-held PIN and the app's unlock secret as one account credential. One
 * controller owns activation, PATCH and GET (docs/08-projektrahmen.md A11).
 */
@RestController
@Tag(name = "Tool: KOBIL", description = "Gerätebindung über den externen Dienstleister KOBIL")
@SecurityRequirement(name = "dpop")
class EnrollKobilToolController(
    private val handler: EnrollKobilToolHandler,
    private val toolEndpoint: ToolEndpoint,
) {

    @PostMapping("/orchestrator/api/v1/channels/{channelSessionId}/tools/enroll-kobil")
    @Operation(
        summary = "Activate enroll-kobil",
        description = "Provisions the KOBIL user and returns everything the app's SDK needs for its " +
            "activation call - including the PIN, which this backend mints and keeps.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED", "currentAcr": "loa2", "currentAmr": ["fsc"]},
                      "next": {"type": "tool", "toolId": "enroll-kobil", "step": "activate", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"},
                      "stepData": {"missingFields": ["activated", "biometricConsent"], "tenantId": "dpop-demo", "kobilUserId": "kob-1a2b3c4d5e6f", "activationCode": "K7M2PQX9", "pin": "40318827", "unlockSecret": "xE1r..."}
                    }
                """)])]
            )
        ]
    )
    fun activate(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        uriBuilder: UriComponentsBuilder,
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.beginActivation(channelSessionId, bindingKeyRef, ENROLL_KOBIL_TOOL_ID)
        val outcome = handler.start(context.toolSessionId)
        val response = toolEndpoint.applyOutcome(context, outcome)
        val location = toolEndpoint.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/enroll-kobil")
    @Operation(
        summary = "Confirm the KOBIL activation",
        description = "Records the credential once KOBIL reports a bound device for this user.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED", "currentAcr": "loa2", "currentAmr": ["fsc", "kobil", "biometric"]},
                      "next": {"type": "orchestrator", "context": "authentication", "step": "authenticated"}
                    }
                """)])]
            )
        ]
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: EnrollKobilPatchRequest?,
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadCurrent(toolSessionId, bindingKeyRef, ENROLL_KOBIL_TOOL_ID)
        val outcome = handler.patch(
            toolSessionId,
            request?.activated,
            request?.biometricConsent,
            bindingKeyRef,
            request?.label,
        )
        return ResponseEntity.ok(toolEndpoint.applyOutcome(context, outcome))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/enroll-kobil")
    @Operation(summary = "Read the current enroll-kobil state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, ENROLL_KOBIL_TOOL_ID)
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
