package com.example.dpop.orchestrator.dpop

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class DpopReplayProtectionService {

    private val seenProofs = ConcurrentHashMap<String, Instant>()

    fun validateAndStore(thumbprint: String, jti: String?, expiresAt: Instant) {
        cleanupExpiredEntries()

        val key = "$thumbprint:$jti"
        val existing = seenProofs.putIfAbsent(key, expiresAt)
        if (existing != null && existing.isAfter(Instant.now())) {
            throw DpopValidationException("DPoP proof replay detected")
        }

        seenProofs[key] = expiresAt
    }

    private fun cleanupExpiredEntries() {
        val now = Instant.now()
        seenProofs.entries.removeIf { !it.value.isAfter(now) }
    }
}
