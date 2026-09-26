package com.example.dpop.orchestrator.kc

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Fetches and caches Keycloak's JWKS for peer-auth signature verification
 * (docs/12-entscheidungen.md ADR-7). Refetches on an unknown `kid` rather than on every request, so key
 * rotation on Keycloak's side doesn't need a restart here - but at most once per [MIN_REFETCH_INTERVAL]:
 * a stream of assertions with made-up `kid`s must not turn every request into a fetch against
 * Keycloak (review 2026-09, Phase F).
 */
@Component
class KeycloakJwkSource(
    @Value("\${kc.peer-auth.jwks-uri}") private val jwksUri: String,
    @Value("\${kc.peer-auth.jwks-cache-ttl-seconds:600}") private val cacheTtlSeconds: Long,
    // Only under the `keycloak` profile - its certificate policy for the JWKS fetch. Absent (tests,
    // no-Keycloak profile), the plain JVM defaults apply.
    private val keycloakHttp: KeycloakHttp? = null,
) {
    private val lock = ReentrantLock()
    private var cached: JWKSet? = null
    private var cachedAt: Instant = Instant.EPOCH

    /** Null for an unknown kid - and for every kid when no issuer is configured (blank jwks-uri, no `keycloak` profile). */
    fun find(kid: String): JWK? {
        if (jwksUri.isBlank()) return null
        val known = currentSet().getKeyByKeyId(kid)
        if (known != null) return known
        // Unknown kid: could be a just-rotated key, worth one refetch before giving up - unless the
        // set was fetched moments ago, in which case a rotation is not what happened.
        return refreshUnlessRecent().getKeyByKeyId(kid)
    }

    private fun currentSet(): JWKSet = lock.withLock {
        val existing = cached
        if (existing != null && Instant.now().isBefore(cachedAt.plusSeconds(cacheTtlSeconds))) {
            existing
        } else {
            fetchAndCache()
        }
    }

    private fun refreshUnlessRecent(): JWKSet = lock.withLock {
        val existing = cached
        if (existing != null && Instant.now().isBefore(cachedAt.plus(MIN_REFETCH_INTERVAL))) existing else fetchAndCache()
    }

    private fun fetchAndCache(): JWKSet {
        val fetched = keycloakHttp?.let { JWKSet.parse(it.getText(jwksUri)) }
            ?: JWKSet.load(URI.create(jwksUri).toURL())
        cached = fetched
        cachedAt = Instant.now()
        return fetched
    }

    private companion object {
        val MIN_REFETCH_INTERVAL: java.time.Duration = java.time.Duration.ofSeconds(30)
    }
}
