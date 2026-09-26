package com.example.dpop.orchestrator.api.v1.kc

import java.util.UUID
import com.example.dpop.tool_spi.ToolId
import com.example.dpop.tool_spi.PASSWORD_EXISTS_MARKER
import com.example.dpop.tool_spi.ClaimSource
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.orchestrator.domain.AcrLevels
import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.kc.PeerAuthValidationException
import com.example.dpop.orchestrator.kc.PeerAuthValidator
import com.example.dpop.orchestrator.domain.OrchestratorException
import com.example.dpop.orchestrator.domain.ChannelType
import com.example.dpop.orchestrator.session.LoginThrottleService
import com.example.dpop.texts.Text
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
import com.example.dpop.tool_api.API_V1

private const val PASSWORD_METHOD = "password"
private val ENROLL_PASSWORD_TOOL = ToolId("enroll-password")

data class MgmtPasswordVerifyRequest(val password: String? = null)
data class MgmtPasswordVerifyResponse(val valid: Boolean)
data class MgmtPasswordSetRequest(val newPassword: String? = null)

/**
 * Stateless, Channel/ToolSession-free password verify/set for Keycloak's native password
 * credential (`OrchestratorPasswordStorageProvider`). The
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
    private val passwordCredentialPort: PasswordCredentialPort,
    private val loginThrottleService: LoginThrottleService
) {

    @PostMapping("$API_V1/tools/auth-password/mgmt/{accountId}")
    @Operation(summary = "Verify a candidate password against the account's stored credential")
    fun verify(
        @PathVariable accountId: Long,
        @RequestHeader("Authorization") authorization: String?,
        @RequestBody(required = false) request: MgmtPasswordVerifyRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<MgmtPasswordVerifyResponse> {
        validatePeerAuth(authorization, httpRequest, accountId)

        // The same per-account lockout as the app channel's auth-password (review 2026-09, M-8):
        // Keycloak's password form is just another place to guess the same password, so it counts
        // against the same budget. Locked, the answer is `false` - but the hash is still computed,
        // so a locked account costs the same time as any other and does not reveal the lock.
        val locked = loginThrottleService.isLocked(accountId)
        val enrollmentRef = accountDirectory.activeEnrollment(accountId, PASSWORD_METHOD)
        val matches = passwordCredentialPort.verify(enrollmentRef, request?.password.orEmpty())
        if (!locked) {
            if (matches) loginThrottleService.recordSuccess(accountId) else loginThrottleService.recordFailure(accountId, ChannelType.KEYCLOAK.name, PASSWORD_METHOD)
        }
        return ResponseEntity.ok(MgmtPasswordVerifyResponse(matches && !locked))
    }

    @PostMapping("$API_V1/tools/enroll-password/mgmt/{accountId}")
    @Operation(summary = "Replace the account's password credential with a new one")
    fun set(
        @PathVariable accountId: Long,
        @RequestHeader("Authorization") authorization: String?,
        @RequestBody request: MgmtPasswordSetRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Void> {
        validatePeerAuth(authorization, httpRequest, accountId)

        // Only REPLACES an existing password (review 2026-09, M-8). Reachable through Keycloak's
        // admin "reset password" (manage-users), it must not become a way to give an account a
        // password it never had - that is enroll-password's job, behind the MANAGE gate.
        if (accountDirectory.activeEnrollment(accountId, PASSWORD_METHOD) == null) {
            throw OrchestratorException.invalidState(Text("Für dieses Konto ist kein Passwort eingerichtet"), "accountId=$accountId")
        }
        val newPassword = requireNotNull(request.newPassword) { "newPassword is required" }
        val enrollmentRef = passwordCredentialPort.setNew(newPassword)
        // The same two writes a normal enroll-password journey makes (JourneyActionExecutor
        // performAdoptCredential), in the same order: first the claim that a password exists, naming
        // the instance, then the instance itself, which deactivates the previous singleton "password"
        // entry. Without the claim, revoking this password later would retract nothing and leave
        // "has a password" standing - which enroll-kobil requires (review 2026-09, M-13).
        val instanceId = UUID.randomUUID()
        accountService.recordClaims(
            accountId,
            listOf(Claim(AttributeType.PASSWORD_EXISTS, PASSWORD_EXISTS_MARKER, ClaimSource.of(ENROLL_PASSWORD_TOOL))),
            provenAcr = AcrLevels.DEFAULT_REQUIRED_ACR,
            authMethodId = instanceId
        )
        accountService.addAuthenticationMethod(
            accountId = accountId,
            method = PASSWORD_METHOD,
            enrollmentRef = enrollmentRef,
            enrolledUnderAcr = null,
            details = mapOf("source" to "kc-native"),
            instanceId = instanceId
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
