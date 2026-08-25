package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.account.AccountService
import com.example.dpop.auth_password.AuthPasswordLookupToolHandler
import com.example.dpop.orchestrator.api.v1.DpopBaseController
import com.example.dpop.orchestrator.api.v1.channel.ChannelResponse
import com.example.dpop.orchestrator.dpop.DpopValidator
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.tool_spi.EnrollmentRef
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

private const val AUTH_PASSWORD_LOOKUP_TOOL_ID = "auth-password-lookup"

data class AuthPasswordLookupPatchRequest(val email: String? = null, val password: String? = null)

/**
 * toolId=auth-password-lookup (docs/04-orchestrierung.md, lookup-based login). One controller
 * owns activation, PATCH and GET for this tool (docs/08-projektrahmen.md A11) - no generic
 * toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: auth-password-lookup", description = "Login ohne DPoP via email + password")
@SecurityRequirement(name = "dpop")
class AuthPasswordLookupToolController(
    dpopValidator: DpopValidator,
    jwkThumbprintService: JwkThumbprintService,
    private val handler: AuthPasswordLookupToolHandler,
    private val accountService: AccountService,
    private val controllerSupport: ToolControllerSupport
) : DpopBaseController(dpopValidator, jwkThumbprintService) {

    @PostMapping("/orchestrator/api/v1/app/channels/{channelSessionId}/tools/auth-password-lookup")
    @Operation(summary = "Activate auth-password-lookup", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.beginActivation(channelSessionId, bindingKeyRef, AUTH_PASSWORD_LOOKUP_TOOL_ID, ToolCategory.AUTH)
        val outcome = handler.start(context.toolSession.toolSessionId!!)
        val response = controllerSupport.applyOutcome(AUTH_PASSWORD_LOOKUP_TOOL_ID, outcome, context)
        val location = controllerSupport.activationLocation(uriBuilder, context.toolSession.toolSessionId!!, AUTH_PASSWORD_LOOKUP_TOOL_ID)
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-password-lookup")
    @Operation(summary = "Supply email and password together (self-verifying, single call)")
    fun patch(
        @PathVariable toolSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        @RequestBody(required = false) request: AuthPasswordLookupPatchRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.loadContext(toolSessionId, bindingKeyRef)
        controllerSupport.requireCurrentTool(context, AUTH_PASSWORD_LOOKUP_TOOL_ID)

        val body = request ?: AuthPasswordLookupPatchRequest()
        // Resolved HERE, at the call site - auth_password may not depend on `account`
        // (docs/08-projektrahmen.md A11). Both null when the email is unknown or has no active
        // password method; the handler treats that identically to a wrong password.
        val account = body.email?.let { accountService.findAccountByEmail(it) }
        val enrollmentRef = account?.accountId?.let { resolveEnrollmentRef(it) }
        val outcome = handler.patch(toolSessionId, body.email, body.password, account?.accountId, enrollmentRef)

        return ResponseEntity.ok(controllerSupport.applyOutcome(AUTH_PASSWORD_LOOKUP_TOOL_ID, outcome, context))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-password-lookup")
    @Operation(summary = "Read the current auth-password-lookup state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.loadContext(toolSessionId, bindingKeyRef)
        val outcome = if (controllerSupport.isCurrentTool(context, AUTH_PASSWORD_LOOKUP_TOOL_ID)) {
            checkNotNull(handler.read(toolSessionId) as? ToolOutcome.InProgress) {
                "read() must return InProgress while the tool is still current"
            }
        } else {
            null
        }
        return ResponseEntity.ok(controllerSupport.buildReadResponse(toolSessionId, AUTH_PASSWORD_LOOKUP_TOOL_ID, context, outcome))
    }

    private fun resolveEnrollmentRef(accountId: Long): EnrollmentRef? {
        val method = accountService.findActiveMethod(accountId, handler.method) ?: return null
        val raw = method.details?.get("enrollmentRef") as? Map<*, *> ?: return null
        val type = raw["type"] as? String ?: return null
        val id = raw["id"] as? String ?: return null
        return EnrollmentRef(type, id)
    }
}
