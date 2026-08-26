package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.account.AccountService
import com.example.dpop.auth_sms.AuthSmsLookupToolHandler
import com.example.dpop.orchestrator.api.v1.DpopBaseController
import com.example.dpop.tool_api.ChannelResponse
import com.example.dpop.tool_api.ToolEndpoint
import com.example.dpop.orchestrator.dpop.DpopValidator
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.tool_spi.EnrollmentRef
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

private const val AUTH_SMS_LOOKUP_TOOL_ID = "auth-sms-lookup"

data class AuthSmsLookupPatchRequest(val email: String? = null, val tan: String? = null)

/**
 * toolId=auth-sms-lookup (docs/04-orchestrierung.md, lookup-based login). One controller owns
 * activation, PATCH and GET for this tool (docs/08-projektrahmen.md A11) - no generic toolId
 * dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: auth-sms-lookup", description = "Login ohne DPoP via email + SMS TAN")
@SecurityRequirement(name = "dpop")
class AuthSmsLookupToolController(
    dpopValidator: DpopValidator,
    jwkThumbprintService: JwkThumbprintService,
    private val handler: AuthSmsLookupToolHandler,
    private val accountService: AccountService,
    private val toolEndpoint: ToolEndpoint
) : DpopBaseController(dpopValidator, jwkThumbprintService) {

    @PostMapping("/orchestrator/api/v1/app/channels/{channelSessionId}/tools/auth-sms-lookup")
    @Operation(summary = "Activate auth-sms-lookup", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = toolEndpoint.beginActivation(channelSessionId, bindingKeyRef, AUTH_SMS_LOOKUP_TOOL_ID)
        val outcome = handler.start(context.toolSessionId)
        val response = toolEndpoint.applyOutcome(AUTH_SMS_LOOKUP_TOOL_ID, outcome, context)
        val location = toolEndpoint.activationLocation(uriBuilder.build().toUri(), context.toolSessionId, AUTH_SMS_LOOKUP_TOOL_ID)
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-sms-lookup")
    @Operation(
        summary = "Supply email, then TAN",
        description = "First call with email resolves the account and triggers the TAN send; a second call with tan confirms it."
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        @RequestBody(required = false) request: AuthSmsLookupPatchRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef)
        toolEndpoint.requireCurrentTool(context, AUTH_SMS_LOOKUP_TOOL_ID)

        val body = request ?: AuthSmsLookupPatchRequest()
        val outcome = if (body.tan != null) {
            handler.patch(toolSessionId, body.tan)
        } else if (body.email != null) {
            // Resolved HERE, at the call site - auth_sms may not depend on `account`
            // (docs/08-projektrahmen.md A11). Both null when the email is unknown or has no
            // active sms method; the handler treats that identically to a wrong TAN.
            val account = accountService.findAccountByEmail(body.email)
            val enrollmentRef = account?.accountId?.let { resolveEnrollmentRef(it) }
            handler.submitEmail(toolSessionId, account?.accountId, enrollmentRef)
        } else {
            handler.patch(toolSessionId, null)
        }

        return ResponseEntity.ok(toolEndpoint.applyOutcome(AUTH_SMS_LOOKUP_TOOL_ID, outcome, context))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/auth-sms-lookup")
    @Operation(summary = "Read the current auth-sms-lookup state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef)
        val outcome = if (toolEndpoint.isCurrentTool(context, AUTH_SMS_LOOKUP_TOOL_ID)) {
            checkNotNull(handler.read(toolSessionId) as? ToolOutcome.InProgress) {
                "read() must return InProgress while the tool is still current"
            }
        } else {
            null
        }
        return ResponseEntity.ok(toolEndpoint.buildReadResponse(toolSessionId, AUTH_SMS_LOOKUP_TOOL_ID, context, outcome))
    }

    private fun resolveEnrollmentRef(accountId: Long): EnrollmentRef? {
        val method = accountService.findActiveMethod(accountId, handler.method) ?: return null
        val raw = method.details?.get("enrollmentRef") as? Map<*, *> ?: return null
        val type = raw["type"] as? String ?: return null
        val id = raw["id"] as? String ?: return null
        return EnrollmentRef(type, id)
    }
}
