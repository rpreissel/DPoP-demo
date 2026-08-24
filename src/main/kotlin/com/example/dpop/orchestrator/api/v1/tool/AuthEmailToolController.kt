package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.account.AccountService
import com.example.dpop.auth_email.AuthEmailUseToolHandler
import com.example.dpop.orchestrator.api.v1.DpopBaseController
import com.example.dpop.orchestrator.dpop.DpopValidator
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.tool_spi.ToolCategory
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
import java.util.UUID

private const val AUTH_EMAIL_TOOL_ID = "auth-email"

data class AuthEmailPatchRequest(val code: String? = null)

/**
 * toolId=auth-email (device-linked case). One controller owns activation, PATCH and GET for
 * this tool (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: auth-email", description = "Email-based authentication (login/step-up)")
@SecurityRequirement(name = "dpop")
class AuthEmailToolController(
    dpopValidator: DpopValidator,
    jwkThumbprintService: JwkThumbprintService,
    private val handler: AuthEmailUseToolHandler,
    private val accountService: AccountService,
    private val controllerSupport: ToolControllerSupport
) : DpopBaseController(dpopValidator, jwkThumbprintService) {

    @PostMapping("/orchestrator/api/v1/app/channels/{channelSessionId}/tool-activate/auth-email")
    @Operation(summary = "Activate auth-email", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ToolStateResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.beginActivation(channelSessionId, bindingKeyRef, AUTH_EMAIL_TOOL_ID, ToolCategory.AUTH)

        // Resolved and null-checked HERE, at the call site - the handler never sees a nullable
        // value (docs/06-ablaeufe.md #3: only the orchestrator may reference `account`).
        val email = resolveConfirmedEmail(context.channel.accountId)
            ?: throw UnresolvableReferenceException("Keine bestaetigte E-Mail-Adresse fuer diesen Account")
        val outcome = handler.start(context.toolSession.toolSessionId!!, email)

        return ResponseEntity.status(HttpStatus.CREATED).body(controllerSupport.applyOutcome(AUTH_EMAIL_TOOL_ID, outcome, context))
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-email")
    @Operation(summary = "Confirm the code sent to the account's confirmed email address")
    fun patch(
        @PathVariable toolSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        @RequestBody(required = false) request: AuthEmailPatchRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ToolStateResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.loadContext(toolSessionId, bindingKeyRef)
        controllerSupport.requireCurrentTool(context, AUTH_EMAIL_TOOL_ID)

        val body = request ?: AuthEmailPatchRequest()
        val outcome = handler.patch(toolSessionId, body.code)

        return ResponseEntity.ok(controllerSupport.applyOutcome(AUTH_EMAIL_TOOL_ID, outcome, context))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-email")
    @Operation(summary = "Read the current auth-email state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ToolStateResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.loadContext(toolSessionId, bindingKeyRef)
        val outcome = if (controllerSupport.isCurrentTool(context, AUTH_EMAIL_TOOL_ID)) {
            checkNotNull(handler.read(toolSessionId) as? ToolOutcome.InProgress) {
                "read() must return InProgress while the tool is still current"
            }
        } else {
            null
        }
        return ResponseEntity.ok(controllerSupport.buildReadResponse(toolSessionId, AUTH_EMAIL_TOOL_ID, context, outcome))
    }

    private fun resolveConfirmedEmail(accountId: Long?): String? {
        val account = accountId?.let { accountService.findAccount(it) } ?: return null
        if (!account.emailConfirmed) return null
        return account.email
    }
}
