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
 * rotation on Keycloak's side doesn't need a restart here.
 */
@Component
class KeycloakJwkSource(
    @Value("\${kc.peer-auth.jwks-uri}") private val jwksUri: String,
    @Value("\${kc.peer-auth.jwks-cache-ttl-seconds:600}") private val cacheTtlSeconds: Long
) {
    private val lock = ReentrantLock()
    private var cached: JWKSet? = null
    private var cachedAt: Instant = Instant.EPOCH

    fun find(kid: String): JWK? {
        val known = currentSet().getKeyByKeyId(kid)
        if (known != null) return known
        // Unknown kid: could be a just-rotated key, worth one refetch before giving up.
        return refresh().getKeyByKeyId(kid)
    }

    private fun currentSet(): JWKSet = lock.withLock {
        val existing = cached
        if (existing != null && Instant.now().isBefore(cachedAt.plusSeconds(cacheTtlSeconds))) {
            existing
        } else {
            fetchAndCache()
        }
    }

    private fun refresh(): JWKSet = lock.withLock { fetchAndCache() }

    private fun fetchAndCache(): JWKSet {
        val fetched = JWKSet.load(URI.create(jwksUri).toURL())
        cached = fetched
        cachedAt = Instant.now()
        return fetched
    }
}
