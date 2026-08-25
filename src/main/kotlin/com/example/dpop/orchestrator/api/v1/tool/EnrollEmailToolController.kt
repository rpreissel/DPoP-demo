package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.account.AccountService
import com.example.dpop.auth_email.EnrollEmailToolHandler
import com.example.dpop.orchestrator.api.v1.DpopBaseController
import com.example.dpop.orchestrator.api.v1.channel.ChannelResponse
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
    dpopValidator: DpopValidator,
    jwkThumbprintService: JwkThumbprintService,
    private val handler: EnrollEmailToolHandler,
    private val accountService: AccountService,
    private val controllerSupport: ToolControllerSupport
) : DpopBaseController(dpopValidator, jwkThumbprintService) {

    @PostMapping("/orchestrator/api/v1/app/channels/{channelSessionId}/tools/enroll-email")
    @Operation(summary = "Activate enroll-email", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.beginActivation(channelSessionId, bindingKeyRef, ENROLL_EMAIL_TOOL_ID, ToolCategory.ENROLL)
        val outcome = handler.start(context.toolSession.toolSessionId!!)
        val response = controllerSupport.applyOutcome(ENROLL_EMAIL_TOOL_ID, outcome, context)
        val location = controllerSupport.activationLocation(uriBuilder, context.toolSession.toolSessionId!!, ENROLL_EMAIL_TOOL_ID)
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/enroll-email")
    @Operation(
        summary = "Supply email, then the confirmation code",
        description = "First call with email triggers the code send; a second call with code confirms it."
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        @RequestBody(required = false) request: EnrollEmailPatchRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.loadContext(toolSessionId, bindingKeyRef)
        controllerSupport.requireCurrentTool(context, ENROLL_EMAIL_TOOL_ID)

        val body = request ?: EnrollEmailPatchRequest()
        // Cross-module existence check resolved HERE, at the call site - the handler never
        // reads `account` itself (docs/03-tool-architektur.md #2: module decoupling).
        val emailTaken = body.email?.let { accountService.existsByEmail(it.trim().lowercase()) } ?: false
        val outcome = handler.patch(toolSessionId, body.email, body.code, emailTaken)

        return ResponseEntity.ok(controllerSupport.applyOutcome(ENROLL_EMAIL_TOOL_ID, outcome, context))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/enroll-email")
    @Operation(summary = "Read the current enroll-email state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.loadContext(toolSessionId, bindingKeyRef)
        val outcome = if (controllerSupport.isCurrentTool(context, ENROLL_EMAIL_TOOL_ID)) {
            checkNotNull(handler.read(toolSessionId) as? ToolOutcome.InProgress) {
                "read() must return InProgress while the tool is still current"
            }
        } else {
            null
        }
        return ResponseEntity.ok(controllerSupport.buildReadResponse(toolSessionId, ENROLL_EMAIL_TOOL_ID, context, outcome))
    }
}
