package com.example.dpop.orchestrator.api.v1.kc

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.kc.PeerAuthValidationException
import com.example.dpop.orchestrator.kc.PeerAuthValidator
import com.example.dpop.tool_api.AccountDirectory
import com.example.dpop.tool_api.PasswordCredentialPort
import com.example.dpop.tool_api.buildRequestUrl
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

private const val PASSWORD_METHOD = "password"

data class MgmtPasswordVerifyRequest(val password: String? = null)
data class MgmtPasswordVerifyResponse(val valid: Boolean)
data class MgmtPasswordSetRequest(val newPassword: String? = null)

/**
 * Stateless, Channel/ToolSession-free password verify/set for Keycloak's native password
 * credential (docs/ideen/web-keycloak-kanal.md, `OrchestratorPasswordStorageProvider`). The
 * account id is already known to the caller (Keycloak's `orchestratorAccountId` user attribute),
 * so unlike the app-channel `auth-password`/`enroll-password` tools there is no Channel/journey to
 * anchor to - `accountId` sits in the URL path instead, which the kc-peer-auth signature's `htu`
 * claim already binds. The peer-auth assertion's normally-channel-scoped `channel_anchor` claim is
 * repurposed here to carry the account id instead (checked explicitly below, since
 * [PeerAuthValidator.validate] only requires the claim to be present, not any particular value) -
 * no new claim type, no change to [PeerAuthValidator]/the Keycloak-side signer.
 */
@RestController
@Tag(name = "KC password management", description = "Stateless password verify/set for Keycloak's native credential")
@SecurityRequirement(name = "kc-peer-auth")
class MgmtPasswordController(
    private val peerAuthValidator: PeerAuthValidator,
    private val accountDirectory: AccountDirectory,
    private val accountService: AccountService,
    private val passwordCredentialPort: PasswordCredentialPort
) {

    @PostMapping("/orchestrator/api/v1/tools/auth-password/mgmt/{accountId}")
    @Operation(summary = "Verify a candidate password against the account's stored credential")
    fun verify(
        @PathVariable accountId: Long,
        @RequestHeader("Authorization") authorization: String?,
        @RequestBody(required = false) request: MgmtPasswordVerifyRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<MgmtPasswordVerifyResponse> {
        validatePeerAuth(authorization, httpRequest, accountId)

        val enrollmentRef = accountDirectory.activeEnrollment(accountId, PASSWORD_METHOD)
        val valid = passwordCredentialPort.verify(enrollmentRef, request?.password.orEmpty())
        return ResponseEntity.ok(MgmtPasswordVerifyResponse(valid))
    }

    @PostMapping("/orchestrator/api/v1/tools/enroll-password/mgmt/{accountId}")
    @Operation(summary = "Replace the account's password credential with a new one")
    fun set(
        @PathVariable accountId: Long,
        @RequestHeader("Authorization") authorization: String?,
        @RequestBody request: MgmtPasswordSetRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Void> {
        validatePeerAuth(authorization, httpRequest, accountId)

        val newPassword = requireNotNull(request.newPassword) { "newPassword is required" }
        val enrollmentRef = passwordCredentialPort.setNew(newPassword)
        // Same effect JourneyService's Action.AdoptCredential branch has after a normal
        // enroll-password journey completes - deactivates the previous singleton "password" entry.
        accountService.addAuthenticationMethod(
            accountId = accountId,
            method = PASSWORD_METHOD,
            enrollmentRef = enrollmentRef,
            enrolledUnderAcr = null,
            details = mapOf("source" to "kc-native")
        )
        return ResponseEntity.noContent().build()
    }

    private fun validatePeerAuth(authorization: String?, httpRequest: HttpServletRequest, accountId: Long) {
        val assertion = peerAuthValidator.validate(bearerToken(authorization), httpRequest.method, buildRequestUrl(httpRequest))
        if (assertion.channelAnchor != accountId.toString()) {
            throw PeerAuthValidationException("Peer-auth channel_anchor does not match accountId")
        }
    }

    private fun bearerToken(authorization: String?): String {
        val value = authorization?.trim() ?: throw PeerAuthValidationException("Missing Authorization header")
        return if (value.startsWith("Bearer ", ignoreCase = true)) value.substring(7).trim() else value
    }
}
