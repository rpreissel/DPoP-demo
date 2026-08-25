package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.account.AccountService
import com.example.dpop.auth_email.AuthEmailLookupToolHandler
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

private const val AUTH_EMAIL_LOOKUP_TOOL_ID = "auth-email-lookup"

data class AuthEmailLookupPatchRequest(val email: String? = null, val code: String? = null)

/**
 * toolId=auth-email-lookup (docs/04-orchestrierung.md, lookup-based login). One controller owns
 * activation, PATCH and GET for this tool (docs/08-projektrahmen.md A11) - no generic toolId
 * dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: auth-email-lookup", description = "Login ohne DPoP via bestaetigte E-Mail-Adresse + Code")
@SecurityRequirement(name = "dpop")
class AuthEmailLookupToolController(
    dpopValidator: DpopValidator,
    jwkThumbprintService: JwkThumbprintService,
    private val handler: AuthEmailLookupToolHandler,
    private val accountService: AccountService,
    private val controllerSupport: ToolControllerSupport
) : DpopBaseController(dpopValidator, jwkThumbprintService) {

    @PostMapping("/orchestrator/api/v1/app/channels/{channelSessionId}/tools/auth-email-lookup")
    @Operation(summary = "Activate auth-email-lookup", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.beginActivation(channelSessionId, bindingKeyRef, AUTH_EMAIL_LOOKUP_TOOL_ID, ToolCategory.AUTH)
        val outcome = handler.start(context.toolSession.toolSessionId!!)
        val response = controllerSupport.applyOutcome(AUTH_EMAIL_LOOKUP_TOOL_ID, outcome, context)
        val location = controllerSupport.activationLocation(uriBuilder, context.toolSession.toolSessionId!!, AUTH_EMAIL_LOOKUP_TOOL_ID)
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-email-lookup")
    @Operation(
        summary = "Supply email, then code",
        description = "First call with email resolves the account and triggers the confirmation code send; a second call with code confirms it."
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        @RequestBody(required = false) request: AuthEmailLookupPatchRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.loadContext(toolSessionId, bindingKeyRef)
        controllerSupport.requireCurrentTool(context, AUTH_EMAIL_LOOKUP_TOOL_ID)

        val body = request ?: AuthEmailLookupPatchRequest()
        val outcome = if (body.code != null) {
            handler.patch(toolSessionId, body.code)
        } else if (body.email != null) {
            // Resolved HERE, at the call site - auth_email may not depend on `account`
            // (docs/08-projektrahmen.md A11). Both null when the email is unknown or unconfirmed;
            // the handler treats that identically to a wrong code (enumeration protection).
            val account = accountService.findAccountByEmail(body.email)
            val confirmedEmail = account?.takeIf { it.emailConfirmed }?.email
            handler.submitEmail(toolSessionId, account?.accountId, confirmedEmail)
        } else {
            handler.patch(toolSessionId, null)
        }

        return ResponseEntity.ok(controllerSupport.applyOutcome(AUTH_EMAIL_LOOKUP_TOOL_ID, outcome, context))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-email-lookup")
    @Operation(summary = "Read the current auth-email-lookup state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.loadContext(toolSessionId, bindingKeyRef)
        val outcome = if (controllerSupport.isCurrentTool(context, AUTH_EMAIL_LOOKUP_TOOL_ID)) {
            checkNotNull(handler.read(toolSessionId) as? ToolOutcome.InProgress) {
                "read() must return InProgress while the tool is still current"
            }
        } else {
            null
        }
        return ResponseEntity.ok(controllerSupport.buildReadResponse(toolSessionId, AUTH_EMAIL_LOOKUP_TOOL_ID, context, outcome))
    }
}
