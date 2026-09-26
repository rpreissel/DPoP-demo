package com.example.dpop.orchestrator.api.v1.kc

import com.example.dpop.orchestrator.channel.KcChannelService
import com.example.dpop.orchestrator.kc.PeerAuthValidationException
import com.example.dpop.orchestrator.kc.PeerAuthValidator
import com.example.dpop.tool_api.API_V1
import com.example.dpop.tool_api.buildRequestUrl
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Keycloak reports a logout here (ADR-39, addendum): the Web channel's logout is Keycloak's own
 * (docs/07-betrieb.md Abschnitt 3), so without this the sign-in log would know only half of them.
 * Keycloak's event listener calls it after the logout, fire-and-forget - the logout never waits on
 * the orchestrator.
 *
 * Peer-auth like every Keycloak call; the assertion's `channel_anchor` is the account id, which the
 * path carries, as with the password endpoints.
 */
@RestController
@Tag(name = "KC sign-out", description = "Keycloak reports a logout for the sign-in log")
@SecurityRequirement(name = "kc-peer-auth")
class KcSignOutController(
    private val peerAuthValidator: PeerAuthValidator,
    private val kcChannelService: KcChannelService,
) {

    @PostMapping("$API_V1/kc/accounts/{accountId}/sign-outs")
    @Operation(summary = "Keycloak ended one session of this account")
    fun signedOut(
        @PathVariable accountId: Long,
        @RequestParam kcSessionId: String,
        @RequestHeader("Authorization") authorization: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<Void> {
        val token = authorization?.trim()?.let { if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else it }
            ?: throw PeerAuthValidationException("Missing Authorization header")
        val assertion = peerAuthValidator.validate(token, httpRequest.method, buildRequestUrl(httpRequest))
        if (assertion.channelAnchor != accountId.toString()) {
            throw PeerAuthValidationException("Peer-auth channel_anchor does not match this account")
        }
        kcChannelService.signedOutAtKeycloak(accountId, kcSessionId)
        return ResponseEntity.noContent().build()
    }
}
