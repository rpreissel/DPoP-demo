package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.auth_email.EnrollEmailToolHandler
import com.example.dpop.tool_api.BindingKey
import com.example.dpop.tool_api.ChannelResponse
import com.example.dpop.tool_api.ToolEndpoint
import com.example.dpop.tool_spi.ToolOutcome
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder

private const val ENROLL_EMAIL_TOOL_ID = "enroll-email"

data class EnrollEmailPatchRequest(
    val email: String? = null,
    val code: String? = null
)

/**
 * toolId=enroll-email. One controller owns activation, PATCH and GET for this tool
 * (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: enroll-email", description = "Registers a confirmed email address as a knowledge/possession factor")
@SecurityRequirement(name = "dpop")
class EnrollEmailToolController(
    private val handler: EnrollEmailToolHandler,
    private val toolEndpoint: ToolEndpoint
) {

    @PostMapping("/orchestrator/api/v1/app/channels/{channelSessionId}/tools/enroll-email")
    @Operation(summary = "Activate enroll-email", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.beginActivation(channelSessionId, bindingKeyRef, ENROLL_EMAIL_TOOL_ID)
        val outcome = handler.start(context.toolSessionId)
        val response = toolEndpoint.applyOutcome(ENROLL_EMAIL_TOOL_ID, outcome, context)
        val location = toolEndpoint.activationLocation(uriBuilder.build().toUri(), context.toolSessionId, ENROLL_EMAIL_TOOL_ID)
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/enroll-email")
    @Operation(
        summary = "Supply email, then the confirmation code",
        description = "First call with email triggers the code send; a second call with code confirms it."
    )
    // The only tool PATCH that spans a transaction: the handler writes the confirmed address onto
    // Account, and applyOutcome then records the authentication method. Without this bracket the
    // two commit separately, and a failure in between would leave a confirmed email on an account
    // that has no email method to show for it.
    @Transactional
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: EnrollEmailPatchRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef)
        toolEndpoint.requireCurrentTool(context, ENROLL_EMAIL_TOOL_ID)

        val body = request ?: EnrollEmailPatchRequest()
        val accountId = checkNotNull(context.journeyAccountId) { "enroll-email without an account bound to the journey" }
        val outcome = handler.patch(toolSessionId, body.email, body.code, accountId)

        return ResponseEntity.ok(toolEndpoint.applyOutcome(ENROLL_EMAIL_TOOL_ID, outcome, context))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/enroll-email")
    @Operation(summary = "Read the current enroll-email state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef)
        val outcome = if (toolEndpoint.isCurrentTool(context, ENROLL_EMAIL_TOOL_ID)) {
            checkNotNull(handler.read(toolSessionId) as? ToolOutcome.InProgress) {
                "read() must return InProgress while the tool is still current"
            }
        } else {
            null
        }
        return ResponseEntity.ok(toolEndpoint.buildReadResponse(toolSessionId, ENROLL_EMAIL_TOOL_ID, context, outcome))
    }
}
