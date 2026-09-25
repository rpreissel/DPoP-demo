package com.example.dpop.orchestrator.api.v1.kc

import com.example.dpop.orchestrator.kc.KcAccountView
import com.example.dpop.orchestrator.kc.KcAccountViews
import com.example.dpop.orchestrator.kc.PeerAuthValidationException
import com.example.dpop.orchestrator.kc.PeerAuthValidator
import com.example.dpop.tool_api.API_V1
import com.example.dpop.tool_api.buildRequestUrl
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Keycloak's user federation reads accounts here instead of holding a copy of them (review 2026-09,
 * P-3; fahrplan Phase E 26). Every lookup is a single indexed read - by account id (primary key),
 * or by email / username through the unique EMAIL anchor - so the cost does not grow with the number
 * of accounts, and there is deliberately no "list all": Keycloak never needs one.
 *
 * Peer-auth like every Keycloak call. The assertion's `channel_anchor` names what is looked up: the
 * account id for [byId] (as for the password endpoints), [LOOKUP_ANCHOR] for a search by address.
 * The answer is signed (M-9) - it decides which user Keycloak logs in.
 */
@RestController
@Tag(name = "KC account lookup", description = "Read-through account lookup for Keycloak's user federation")
@SecurityRequirement(name = "kc-peer-auth")
class KcAccountLookupController(
    private val peerAuthValidator: PeerAuthValidator,
    private val views: KcAccountViews,
) {

    @GetMapping("$API_V1/kc/accounts/{accountId}")
    @Operation(summary = "The account as Keycloak shows it, by account id")
    fun byId(
        @PathVariable accountId: Long,
        @RequestHeader("Authorization") authorization: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<KcAccountView> {
        validatePeerAuth(authorization, httpRequest, expectedAnchor = accountId.toString())
        return views.byAccountId(accountId).toResponse()
    }

    @GetMapping("$API_V1/kc/accounts")
    @Operation(summary = "The account as Keycloak shows it, by exact email or username - never a list")
    fun search(
        @RequestParam(required = false) email: String?,
        @RequestParam(required = false) username: String?,
        @RequestHeader("Authorization") authorization: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<KcAccountView> {
        validatePeerAuth(authorization, httpRequest, expectedAnchor = LOOKUP_ANCHOR)
        val view = when {
            email != null && username == null -> views.byEmail(email)
            username != null && email == null -> views.byUsername(username)
            else -> return ResponseEntity.badRequest().build()
        }
        return view.toResponse()
    }

    private fun KcAccountView?.toResponse(): ResponseEntity<KcAccountView> =
        this?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    private fun validatePeerAuth(authorization: String?, httpRequest: HttpServletRequest, expectedAnchor: String) {
        val token = authorization?.trim()?.let { if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else it }
            ?: throw PeerAuthValidationException("Missing Authorization header")
        val assertion = peerAuthValidator.validate(token, httpRequest.method, buildRequestUrl(httpRequest))
        if (assertion.channelAnchor != expectedAnchor) {
            throw PeerAuthValidationException("Peer-auth channel_anchor does not match this lookup")
        }
    }

    companion object {
        /** The `channel_anchor` of a search by address - there is no account id to name yet. */
        const val LOOKUP_ANCHOR = "account-lookup"
    }
}
