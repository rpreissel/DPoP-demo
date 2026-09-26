package com.example.dpop.orchestrator.session

import org.springframework.web.client.HttpClientErrorException
import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.kc.AccountTokenResponse
import com.example.dpop.orchestrator.kc.KeycloakAdminClient
import com.example.dpop.orchestrator.domain.policy.AuthPolicy
import com.nimbusds.jwt.SignedJWT
import org.springframework.context.annotation.Profile
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * `keycloak`-profile [TokenProvider]: mints a real, Keycloak-signed access token via the custom
 * `urn:dpop-demo:account-token` grant (keycloak-extension's `AccountTokenGrantType`)
 * instead of [TokenService]'s mock JWT - the App client never talks to Keycloak directly,
 * so the orchestrator is the only party that can fetch it a real token. `APP`-channel-only by
 * construction: [com.example.dpop.orchestrator.channel.ChannelService.getToken] never
 * calls this for a `KEYCLOAK` channel (see that method's own doc) - that client already holds
 * real Keycloak tokens from the standard browser login and refreshes directly against Keycloak.
 *
 * Three branches, same shape as [TokenService.tokenFor] but with the account-token grant standing
 * in for [TokenService]'s mock re-issuance and Keycloak's own `refresh_token` grant standing in
 * for its silent-refresh path: (1) still valid - returned unchanged; (2) expiring but the account's
 * ACR/AMR haven't changed since the last mint - a cheap `refresh_token` call against the very same
 * Keycloak session; (3) no valid RefreshToken (first issuance, or [AuthEvidenceService] just
 * invalidated the cache because a step-up changed the evidence) - the CURRENT acr/amr go through the
 * account-token grant proper. ACR/AMR only ever change between (2) and (3) because a step-up is exactly what
 * [AuthEvidenceService.applyEvidence]/[AuthEvidenceService.applyEvidenceUpdate] already clears the
 * cache for - by the time this method runs, "cache still holds a RefreshToken" already means
 * "ACR/AMR are still the ones that RefreshToken's session was minted with".
 */
@Service
@Profile("keycloak")
class KcTokenProvider(
    private val authContextRepository: AuthContextRepository,
    private val keycloakAdminClient: KeycloakAdminClient,
    private val authEvidenceService: AuthEvidenceService,
    private val authPolicy: AuthPolicy,
    private val accountService: AccountService
) : TokenProvider {

    override fun tokenFor(channel: ChannelSession, minValiditySeconds: Long): TokenPair {
        val authContextId = channel.authContextId!!
        val authContext = checkNotNull(authContextRepository.findByIdOrNull(authContextId)) {
            "AuthContext not found: $authContextId"
        }
        val accountId = checkNotNull(authContext.accountId) { "AuthContext $authContextId has no accountId" }
        val now = Instant.now()

        val currentExpiry = authContext.accessExpiresAt
        if (authContext.accessToken != null && currentExpiry != null &&
            currentExpiry.isAfter(now.plusSeconds(minValiditySeconds))
        ) {
            return TokenPair(authContext.accessToken!!, currentExpiry, authContext.refreshExpiresAt ?: currentExpiry)
        }

        // Only the first token of this login is requested fresh. After that, Keycloak's own session
        // decides (SSO idle and max): an expired or refused refresh ends the login instead of opening
        // a new Keycloak session behind its back (review 2026-09, M-4).
        val refreshToken = authContext.refreshToken
        val response = if (refreshToken == null) {
            requestAccountToken(authContext)
        } else {
            if (authContext.refreshExpiresAt?.isAfter(now) != true) {
                throw SessionExpiredException("Keycloak refresh window of AuthContext $authContextId has lapsed")
            }
            try {
                keycloakAdminClient.refreshAccountToken(refreshToken)
            } catch (e: HttpClientErrorException) {
                throw SessionExpiredException("Keycloak refused the refresh for AuthContext $authContextId: ${e.statusCode}")
            }
        }

        val accessExpiresAt = now.plusSeconds(response.expiresInSeconds)
        authContext.accessToken = response.accessToken
        authContext.accessExpiresAt = accessExpiresAt
        // The Keycloak session THIS token belongs to (its `sid` claim) - not read back for
        // anything here, only kept so a later App-channel logout (JourneyService's
        // Transition.Logout) can end exactly this one session, never every session this account
        // happens to hold.
        authContext.keycloakSessionId = sidClaimOf(response.accessToken) ?: authContext.keycloakSessionId
        if (response.refreshToken != null) {
            authContext.refreshToken = response.refreshToken
            authContext.refreshExpiresAt = response.refreshExpiresInSeconds?.let { now.plusSeconds(it) }
        }
        authContextRepository.save(authContext)

        return TokenPair(response.accessToken, accessExpiresAt, authContext.refreshExpiresAt ?: accessExpiresAt)
    }

    /**
     * Same acr/amr [TokenService.mintAccessToken] bakes into the mock JWT - `AccountTokenGrantType`
     * (keycloak-extension) copies them onto the Keycloak session, so the real token carries them. Sent
     * as plain parameters: only the orchestrator's own client may call the grant (ADR-9, addendum F-6).
     */
    private fun requestAccountToken(authContext: AuthContext): AccountTokenResponse {
        val accountId = checkNotNull(authContext.accountId)
        val account = accountService.findAccount(accountId)
        val evidence = authContext.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) }?.toCoreEvidence()
        return keycloakAdminClient.requestAccountToken(
            accountId,
            acr = evidence?.let { authPolicy.resolveAcr(it, account) }?.value,
            amr = evidence?.amr?.map { it.value }.orEmpty(),
        )
    }

    /** Keycloak's own signature already vouches for this token by the time it reaches us (it came straight from Keycloak's own token endpoint) - parsed unsecured, purely to read the `sid` claim back out. */
    private fun sidClaimOf(accessToken: String): String? =
        runCatching { SignedJWT.parse(accessToken).jwtClaimsSet.getStringClaim("sid") }.getOrNull()

}
