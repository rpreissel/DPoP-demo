package com.example.dpop.orchestrator.kc

import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.springframework.web.util.UriComponentsBuilder

/**
 * The Keycloak-side half of the account-sync mechanism (docs/ideen/web-keycloak-kanal.md):
 * mirrors an orchestrator account into a Keycloak user via the Admin REST API, so
 * `infra/tofu/keycloak/main.tf` no longer needs to hand-declare demo users - every account
 * `AccountService` ever creates/changes/deletes gets its Keycloak user kept in sync automatically
 * (see [KeycloakAccountSyncListener]). Only wired up under the `keycloak` Spring profile - decided
 * once at startup via `@Profile`, not a runtime toggle.
 *
 * Authenticates as its OWN client's service account (`keycloak-sync.admin-client-id`), which
 * `infra/tofu/keycloak/main.tf` grants the realm-management `manage-users` role - the standard
 * Keycloak pattern for a backend that manages users without a human admin session.
 */
@Component
@Profile("keycloak")
class KeycloakAdminClient(
    // Unused beyond forcing Spring to construct it (and run its @PostConstruct trust-all install)
    // BEFORE this bean - RestClient.Builder.build() below may eagerly build the underlying
    // java.net.http.HttpClient, which snapshots SSLContext.getDefault() at that point; if this ran
    // first, the snapshot would still be the real, non-trusting default.
    @Suppress("UNUSED_PARAMETER") tlsConfig: KeycloakTlsConfig,
    @Value("\${keycloak-sync.base-url}") private val baseUrl: String,
    @Value("\${keycloak-sync.realm}") private val realm: String,
    @Value("\${keycloak-sync.admin-client-id}") private val adminClientId: String,
    @Value("\${keycloak-sync.admin-client-secret}") private val adminClientSecret: String,
    // Demo-only: a real identity system would never hand out a shared default password. This
    // stands in for whatever real onboarding flow would set the user's first credential, purely
    // so the native LoA1 password login (docs/ideen/web-keycloak-kanal.md #9) has something to
    // authenticate a freshly-synced account with.
    @Value("\${keycloak-sync.default-password:Demo1234!}") private val defaultPassword: String
) {
    private val log = LoggerFactory.getLogger(KeycloakAdminClient::class.java)
    private val restClient = RestClient.builder().baseUrl(baseUrl).build()

    @Volatile
    private var cachedToken: CachedToken? = null

    /**
     * Creates the Keycloak user for [accountId] if none exists yet (username derived from
     * [firstName]/[lastName], with the demo default password), else updates its email. The
     * username is chosen ONCE, at creation, and never touched again on any later sync: recomputing
     * it on every call would let it drift (or even collide) as OTHER accounts are created/deleted
     * around it - e.g. "max-muster-2" must stay "max-muster-2" forever once assigned, even after
     * the account originally holding "max-muster" is deleted and that name becomes free again.
     */
    fun upsertUser(accountId: Long, email: String?, firstName: String?, lastName: String?) {
        val existingUserId = findUserId(accountId)
        if (existingUserId == null) {
            val username = uniqueUsername(firstName, lastName, accountId)
            val userId = createUser(accountId, username, email, firstName, lastName)
            resetPassword(userId)
            log.info("Keycloak account sync: created user {} ({}) for accountId={}", userId, username, accountId)
        } else {
            updateEmail(existingUserId, email)
            log.info("Keycloak account sync: updated user {} for accountId={}", existingUserId, accountId)
        }
    }

    /**
     * "<vorname>-<nachname>", lowercased and sanitized; disambiguated with a "-<accountId>" suffix
     * (not an incrementing counter) against whatever Keycloak already holds - accountId is unique
     * and permanent by construction, so this is a one-shot check with no retry loop, and the
     * result can never later collide with a DIFFERENT account's own disambiguated name either.
     * Falls back to the old accountId-based scheme entirely when no name is known (e.g.
     * `ext_stammdaten` has nothing for this person) - still unique, just less readable.
     */
    private fun uniqueUsername(firstName: String?, lastName: String?, accountId: Long): String {
        val base = listOfNotNull(firstName, lastName)
            .joinToString("-") { it.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') }
            .trim('-')
            .ifBlank { "orchestrator-account-$accountId" }
        return if (usernameTaken(base)) "$base-$accountId" else base
    }

    private fun usernameTaken(username: String): Boolean {
        val uri = UriComponentsBuilder.fromPath("/admin/realms/{realm}/users")
            .queryParam("username", username)
            .queryParam("exact", "true")
            .buildAndExpand(realm)
            .toUriString()
        return authorized().get().uri(uri).retrieve().body<List<Map<String, Any?>>>().orEmpty().isNotEmpty()
    }

    fun deleteUser(accountId: Long) {
        val userId = findUserId(accountId) ?: return
        authorized().delete().uri("/admin/realms/{realm}/users/{id}", realm, userId).retrieve().toBodilessEntity()
        log.info("Keycloak account sync: deleted user {} for accountId={}", userId, accountId)
    }

    /**
     * Every accountId currently mirrored into Keycloak, read back off the same
     * `orchestratorAccountId` attribute [upsertUser] writes - the full-reconciliation counterpart
     * ("Sync with Keycloak" action) needs this to find KEYCLOAK-side orphans: a user whose account
     * was deleted in the orchestrator without the corresponding [AccountDeleted] event ever
     * reaching this listener (e.g. the app was down at the time).
     */
    fun findAllSyncedAccountIds(): Set<Long> {
        val accountIds = mutableSetOf<Long>()
        var first = 0
        while (true) {
            val uri = UriComponentsBuilder.fromPath("/admin/realms/{realm}/users")
                .queryParam("briefRepresentation", "false")
                .queryParam("first", first)
                .queryParam("max", PAGE_SIZE)
                .buildAndExpand(realm)
                .toUriString()
            val page = authorized().get().uri(uri).retrieve().body<List<Map<String, Any?>>>().orEmpty()
            page.forEach { user ->
                @Suppress("UNCHECKED_CAST")
                val attributes = user["attributes"] as? Map<String, List<String>>
                attributes?.get("orchestratorAccountId")?.firstOrNull()?.toLongOrNull()?.let { accountIds.add(it) }
            }
            if (page.size < PAGE_SIZE) break
            first += PAGE_SIZE
        }
        return accountIds
    }

    private fun findUserId(accountId: Long): String? {
        val uri = UriComponentsBuilder.fromPath("/admin/realms/{realm}/users")
            .queryParam("q", "orchestratorAccountId:$accountId")
            .buildAndExpand(realm)
            .toUriString()
        val users = authorized().get().uri(uri).retrieve().body<List<Map<String, Any?>>>().orEmpty()
        return users.firstOrNull()?.get("id") as? String
    }

    private fun createUser(accountId: Long, username: String, email: String?, firstName: String?, lastName: String?): String {
        val body = buildMap<String, Any?> {
            put("username", username)
            put("enabled", true)
            if (email != null) put("email", email)
            if (firstName != null) put("firstName", firstName)
            if (lastName != null) put("lastName", lastName)
            put("attributes", mapOf("orchestratorAccountId" to listOf(accountId.toString())))
        }
        val response = authorized().post().uri("/admin/realms/{realm}/users", realm)
            .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity()
        val location = response.headers.location?.toString()
            ?: error("Keycloak did not return a Location header for the created user")
        return location.substringAfterLast('/')
    }

    private fun updateEmail(userId: String, email: String?) {
        if (email == null) return
        authorized().put().uri("/admin/realms/{realm}/users/{id}", realm, userId)
            .contentType(MediaType.APPLICATION_JSON).body(mapOf("email" to email)).retrieve().toBodilessEntity()
    }

    private fun resetPassword(userId: String) {
        val credential = mapOf("type" to "password", "value" to defaultPassword, "temporary" to false)
        authorized().put().uri("/admin/realms/{realm}/users/{id}/reset-password", realm, userId)
            .contentType(MediaType.APPLICATION_JSON).body(credential).retrieve().toBodilessEntity()
    }

    /** [restClient] pre-authorized with a valid (cached, auto-refreshed) service-account access token. */
    private fun authorized(): RestClient =
        restClient.mutate().defaultHeader("Authorization", "Bearer ${accessToken()}").build()

    private fun accessToken(): String {
        val current = cachedToken
        if (current != null && Instant.now().isBefore(current.expiresAt)) return current.value

        val form = "grant_type=client_credentials&client_id=$adminClientId&client_secret=$adminClientSecret"
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
        private const val PAGE_SIZE = 100
    }
}
