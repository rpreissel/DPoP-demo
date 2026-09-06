package com.example.dpop.orchestrator.session

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.kc.AccountKeypairService
import com.example.dpop.orchestrator.kc.AccountTokenResponse
import com.example.dpop.orchestrator.kc.KeycloakAdminClient
import com.example.dpop.orchestrator.policy.AuthPolicy
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.context.annotation.Profile
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * `keycloak`-profile [TokenProvider]: mints a real, Keycloak-signed access token via the custom
 * `urn:dpop-demo:account-token` grant (keycloak-extension's `AccountTokenGrantType`, DPoP-demo-
 * xso.3) instead of [TokenService]'s mock JWT - the App client never talks to Keycloak directly,
 * so the orchestrator is the only party that can fetch it a real token. `APP`-channel-only by
 * construction: [com.example.dpop.orchestrator.api.v1.channel.ChannelService.getToken] never
 * calls this for a `KEYCLOAK` channel (see that method's own doc) - that client already holds
 * real Keycloak tokens from the standard browser login and refreshes directly against Keycloak.
 *
 * Three branches, same shape as [TokenService.tokenFor] but with the account-token grant standing
 * in for [TokenService]'s mock re-issuance and Keycloak's own `refresh_token` grant standing in
 * for its silent-refresh path: (1) still valid - returned unchanged; (2) expiring but the account's
 * ACR/AMR haven't changed since the last mint - a cheap `refresh_token` call against the very same
 * Keycloak session, no assertion/private key involved; (3) no valid RefreshToken (first issuance,
 * or [AuthEvidenceService] just invalidated the cache because a step-up changed the evidence) -
 * a fresh signed assertion, carrying the CURRENT acr/amr as claims, goes through the account-token
 * grant proper. ACR/AMR only ever change between (2) and (3) because a step-up is exactly what
 * [AuthEvidenceService.applyEvidence]/[AuthEvidenceService.applyEvidenceUpdate] already clears the
 * cache for - by the time this method runs, "cache still holds a RefreshToken" already means
 * "ACR/AMR are still the ones that RefreshToken's session was minted with".
 */
@Service
@Profile("keycloak")
class KcTokenProvider(
    private val authContextRepository: AuthContextRepository,
    private val accountKeypairService: AccountKeypairService,
    private val keycloakAdminClient: KeycloakAdminClient,
    private val authEvidenceService: AuthEvidenceService,
    private val authPolicy: AuthPolicy,
    private val accountService: AccountService
) : TokenProvider {

    override fun tokenFor(channel: ChannelSession, minValiditySeconds: Long): TokenPair {
        val authContextId = channel.authContextId!!
        val authContext = requireNotNull(authContextRepository.findByIdOrNull(authContextId)) {
            "AuthContext not found: $authContextId"
        }
        val accountId = requireNotNull(authContext.accountId) { "AuthContext $authContextId has no accountId" }
        val now = Instant.now()

        val currentExpiry = authContext.tokenExpiresAt
        if (authContext.tokenHandle != null && currentExpiry != null &&
            currentExpiry.isAfter(now.plusSeconds(minValiditySeconds))
        ) {
            return TokenPair(authContext.tokenHandle!!, currentExpiry, authContext.refreshExpiresAt ?: currentExpiry)
        }

        val refreshStillValid = authContext.refreshTokenHandle != null &&
            authContext.refreshExpiresAt?.isAfter(now) == true
        val response = if (refreshStillValid) {
            keycloakAdminClient.refreshAccountToken(authContext.refreshTokenHandle!!)
        } else {
            keycloakAdminClient.requestAccountToken(accountId, signAssertion(authContext))
        }

        val accessExpiresAt = now.plusSeconds(response.expiresInSeconds)
        authContext.tokenHandle = response.accessToken
        authContext.tokenExpiresAt = accessExpiresAt
        // The Keycloak session THIS token belongs to (its `sid` claim) - not read back for
        // anything here, only kept so a later App-channel logout (JourneyService's
        // Transition.Logout) can end exactly this one session, never every session this account
        // happens to hold.
        authContext.keycloakSessionId = sidClaimOf(response.accessToken) ?: authContext.keycloakSessionId
        if (response.refreshToken != null) {
            authContext.refreshTokenHandle = response.refreshToken
            authContext.refreshExpiresAt = response.refreshExpiresInSeconds?.let { now.plusSeconds(it) }
        }
        authContextRepository.save(authContext)

        return TokenPair(response.accessToken, accessExpiresAt, authContext.refreshExpiresAt ?: accessExpiresAt)
    }

    /**
     * Same claims [TokenService.mintAccessToken] bakes into the mock JWT, just carried as signed
     * assertion claims instead - `AccountTokenGrantType` (keycloak-extension) copies them onto the
     * Keycloak session so the real token ends up with the same acr/amr.
     *
     * Self-healing on purpose: [accountKeypairService] generates the keypair on demand rather than
     * requiring it to already exist, and every full re-mint re-pushes the public key onto Keycloak
     * regardless. `KeycloakAccountSyncListener`'s own generation (at account-sync time) is normally
     * long done before any token is ever requested - but a brand-new account's very first
     * `.../token` call can race ahead of that `AFTER_COMMIT` listener (observed live: a fresh
     * registration's first token request failed with "no keypair", a retry moments later
     * succeeded). Rather than chase that ordering, this method just never depends on it: it holds
     * everything needed to generate and publish the keypair itself, so first use is always
     * correct instead of relying on a sync that is best-effort by design anyway.
     */
    private fun signAssertion(authContext: AuthContext): String {
        val accountId = requireNotNull(authContext.accountId)
        val keypair = accountKeypairService.keypairFor(accountId)
        val account = accountService.findAccount(accountId)
        val activeMethods = account?.activeAuthenticationMethods?.map { it.method }?.distinct().orEmpty()
        keycloakAdminClient.setPublicKeyCredential(accountId, keypair.publicKeyJwk, activeMethods)
        val evidence = authContext.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) }?.toCoreEvidence()

        val privateKey = ECKey.parse(keypair.privateKeyJwk)
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .subject(accountId.toString())
            .audience(KeycloakAdminClient.ACCOUNT_TOKEN_GRANT_TYPE)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(ASSERTION_TTL_SECONDS)))
            .jwtID(UUID.randomUUID().toString())
            .claim("acr", evidence?.let { authPolicy.resolveAcr(it, account) })
            .claim("amr", evidence?.amr?.map { it.value } ?: emptyList<String>())
            .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(privateKey.keyID).build(), claims)
        jwt.sign(ECDSASigner(privateKey))
        return jwt.serialize()
    }

    /** Keycloak's own signature already vouches for this token by the time it reaches us (it came straight from Keycloak's own token endpoint) - parsed unsecured, purely to read the `sid` claim back out. */
    private fun sidClaimOf(accessToken: String): String? =
        runCatching { SignedJWT.parse(accessToken).jwtClaimsSet.getStringClaim("sid") }.getOrNull()

    companion object {
        private const val ASSERTION_TTL_SECONDS = 60L
    }
}
