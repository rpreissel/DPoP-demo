package com.example.dpop.orchestrator.kc

import com.example.dpop.kcmigrate.federatedUserId
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.springframework.web.util.UriComponentsBuilder

/**
 * The orchestrator's calls into Keycloak's Admin API - not a user mirror (review 2026-09, P-3):
 * Keycloak reads accounts through its user federation (`OrchestratorStorageProvider`, backed by
 * `KcAccountLookupController`), so nothing here creates or updates users. What remains:
 * ending a session ([logoutSession]), clearing up after
 * a deleted account ([removeAccount]), and the account-token grant.
 *
 * A federated user's Keycloak id is computed ([federatedUserId]), never searched for - a lookup by
 * attribute over 10 million+ users is exactly what this design avoids.
 *
 * Authenticates as its OWN client's service account (`keycloak-sync.admin-client-id`).
 *
 * The custom `urn:dpop-demo:account-token` grant ([requestAccountToken]/[refreshAccountToken]) is
 * client-authenticated separately, as `keycloak-sync.app-client-id`
 * (`orchestrator-app-token`, V8) - NOT as the admin client above. `AccountTokenGrantType` never
 * checks the calling client's roles, so it needs no admin/realm-management privilege at all;
 * reusing the admin client there would just be an unused-but-present privilege escalation on the
 * client that mints real end-user tokens.
 */
@Component
@Profile("keycloak")
class KeycloakAdminClient(
    keycloakHttp: KeycloakHttp,
    private val clientAssertions: OrchestratorClientAssertionSigner,
    @Value("\${keycloak-sync.base-url}") private val baseUrl: String,
    @Value("\${keycloak-sync.public-base-url}") private val publicBaseUrl: String,
    @Value("\${keycloak-sync.realm}") private val realm: String,
    @Value("\${keycloak-sync.admin-client-id}") private val adminClientId: String,
    @Value("\${keycloak-sync.app-client-id}") private val appClientId: String
) {
    private val log = LoggerFactory.getLogger(KeycloakAdminClient::class.java)
    private val restClient = keycloakHttp.restClient(baseUrl)

    @Volatile
    private var cachedToken: CachedToken? = null

    /**
     * Ends exactly ONE Keycloak session (`DELETE /admin/realms/{realm}/sessions/{sessionId}`) -
     * the App-channel counterpart of "logout stays with Keycloak" for the Web channel
     * (docs/07-betrieb.md Abschnitt 3): an App-channel `LogoutIntent` completion
     * (`JourneyService`'s `Transition.Logout`) has no browser/cookie of its own to end, but the
     * same account may also hold a real Keycloak session from the custom account-token grant
     * (`AccountTokenGrantType`'s reused session, `AuthContext.keycloakSessionId`).
     * Deliberately the single-session endpoint, not `POST .../users/{id}/logout` (every session):
     * an App-channel logout ends only what THAT channel itself was using, never a Web-channel
     * browser session the same account happens to also be logged into elsewhere. Best-effort by
     * design, like every other call this class's callers wrap: a failed logout here must never
     * turn an already-completed App-channel logout into a failed request.
     */
    fun logoutSession(keycloakSessionId: String) {
        authorized().delete().uri("/admin/realms/{realm}/sessions/{sessionId}", realm, keycloakSessionId).retrieve().toBodilessEntity()
    }

    /**
     * The account is gone: Keycloak drops what it keeps locally for this federated user - sessions,
     * login failures, consents and anything in its federated storage. Through the extension's own
     * endpoint (`AccountRemovalResource`) because the user can no longer be looked up at this point
     * - the account no longer exists - and Keycloak's own `DELETE users/{id}` would first try exactly that.
     */
    fun removeAccount(accountId: Long) {
        authorized().delete().uri("/admin/realms/{realm}/orchestrator-accounts/{accountId}", realm, accountId).retrieve().toBodilessEntity()
        log.info("Keycloak: removed local state of federated user for accountId={}", accountId)
    }

    /**
     * Calls the keycloak-extension's custom `urn:dpop-demo:account-token` grant to mint a real,
     * Keycloak-signed access token for [accountId], carrying [acr] and [amr] - authenticated as the
     * dedicated `orchestrator-app-token` client (`private_key_jwt`), the only client the grant accepts
     * (`AccountTokenGrantClients`, review 2026-09-26 F-1). That client IS the orchestrator; there is no
     * second, per-account proof on top (ADR-9, addendum F-6). Deliberately not [accessToken]'s admin
     * service account: the grant has no business running under admin privileges.
     */
    fun requestAccountToken(accountId: Long, acr: String?, amr: List<String>): AccountTokenResponse {
        val form = "grant_type=$ACCOUNT_TOKEN_GRANT_TYPE" +
            "&${clientAuth(appClientId)}" +
            "&account_id=$accountId" +
            (acr?.let { "&acr=" + URLEncoder.encode(it, StandardCharsets.UTF_8) } ?: "") +
            "&amr=" + URLEncoder.encode(amr.joinToString(","), StandardCharsets.UTF_8)
        return tokenResponse(form)
    }

    /**
     * The cheap renewal path: a plain OAuth2 `refresh_token` grant against the SAME session
     * [requestAccountToken] created/reused - no assertion, no account private key involved, since
     * nothing about the account's ACR/AMR changed (that invariant is [KcTokenProvider]'s to keep,
     * not this method's - it only ever gets called when the caller already decided a refresh is
     * safe). Standard `refresh_token` grants also bump Keycloak's own `lastSessionRefresh`, which
     * is what keeps the reused session's SSO Session Idle timeout alive.
     */
    fun refreshAccountToken(refreshToken: String): AccountTokenResponse {
        val form = "grant_type=refresh_token" +
            "&${clientAuth(appClientId)}" +
            "&refresh_token=$refreshToken"
        return tokenResponse(form)
    }

    /**
     * Die Client-Authentisierung fuer jeden Token-Request: `private_key_jwt` (RFC 7523) statt
     * eines `client_secret` - der Orchestrator signiert eine kurzlebige Assertion, Keycloak prueft
     * sie gegen den Public Key, den es sich von [OrchestratorClientJwksController] holt. Kein
     * geteiltes Geheimnis, dieselbe Linie wie ADR-7 in der Gegenrichtung.
     *
     * Als `aud` die OEFFENTLICHE Realm-Adresse und nicht [baseUrl], ueber die dieser Aufruf
     * tatsaechlich laeuft: Keycloak vergleicht gegen die Issuer-URL, die es aus seiner eigenen
     * Frontend-Konfiguration (KC_HOSTNAME) bildet, nicht gegen den benutzten Weg.
     */
    private fun clientAuth(clientId: String): String {
        val assertion = clientAssertions.assertionFor(clientId, "$publicBaseUrl/realms/$realm")
        return "client_id=$clientId" +
            "&client_assertion_type=${URLEncoder.encode(CLIENT_ASSERTION_TYPE, StandardCharsets.UTF_8)}" +
            "&client_assertion=$assertion"
    }

    private fun tokenResponse(form: String): AccountTokenResponse {
        val response = restClient.post()
            .uri("/realms/{realm}/protocol/openid-connect/token", realm)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body(form)
            .retrieve()
            .body<Map<String, Any?>>()
            ?: error("Keycloak token endpoint returned no body")
        val accessToken = response["access_token"] as? String ?: error("Keycloak token response has no access_token")
        val expiresInSeconds = (response["expires_in"] as? Number)?.toLong() ?: 60L
        val refreshToken = response["refresh_token"] as? String
        val refreshExpiresInSeconds = (response["refresh_expires_in"] as? Number)?.toLong()
        return AccountTokenResponse(accessToken, expiresInSeconds, refreshToken, refreshExpiresInSeconds)
    }

    /** [restClient] pre-authorized with a valid (cached, auto-refreshed) service-account access token. */
    private fun authorized(): RestClient =
        restClient.mutate().defaultHeader("Authorization", "Bearer ${accessToken()}").build()

    private fun accessToken(): String {
        val current = cachedToken
        if (current != null && Instant.now().isBefore(current.expiresAt)) return current.value

        val form = "grant_type=client_credentials&${clientAuth(adminClientId)}"
        val response = restClient.post()
            .uri("/realms/{realm}/protocol/openid-connect/token", realm)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body(form)
            .retrieve()
            .body<Map<String, Any?>>()
            ?: error("Keycloak service-account token request returned no body")

        val token = response["access_token"] as? String ?: error("Keycloak token response has no access_token")
        val expiresInSeconds = (response["expires_in"] as? Number)?.toLong() ?: 60L
        // A minute of slack rather than the exact expiry - avoids a request landing right as the
        // token expires between the cache check above and Keycloak actually receiving it.
        val expiresAt = Instant.now().plusSeconds((expiresInSeconds - 60).coerceAtLeast(5))
        val fresh = CachedToken(token, expiresAt)
        cachedToken = fresh
        return fresh.value
    }

    private data class CachedToken(val value: String, val expiresAt: Instant)

    companion object {
        /** Must match [com.example.dpop.kcext.grant.AccountTokenGrantType.GRANT_TYPE] on the keycloak-extension side. */
        const val ACCOUNT_TOKEN_GRANT_TYPE = "urn:dpop-demo:account-token"

        /** RFC 7523: signierte Client-Assertion statt client_secret. */
        private const val CLIENT_ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
    }
}

data class AccountTokenResponse(
    val accessToken: String,
    val expiresInSeconds: Long,
    val refreshToken: String? = null,
    val refreshExpiresInSeconds: Long? = null
)
