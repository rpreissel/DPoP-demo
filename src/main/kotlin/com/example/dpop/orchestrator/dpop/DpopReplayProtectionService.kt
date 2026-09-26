package com.example.dpop.orchestrator.dpop

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant

/**
 * Single-use enforcement for DPoP and device proofs.
 *
 * Backed by a table, not an in-memory map: the single-use guarantee must survive restarts and
 * hold across every replica (behind more than one node, an in-memory proof could simply be
 * replayed against a different instance), and validation must stay O(1) rather than a sweep over
 * a set whose size the caller controls - any well-formed proof with a fresh `jti` earns an entry
 * for the length of the acceptance window.
 *
 * The insert itself is the check: `proof_hash` is the primary key, so a duplicate raises rather
 * than needing a read-then-write that two concurrent replays could both pass. The key is a digest
 * of `thumbprint:jti`, because `jti` is client-chosen and must not decide the key width.
 */
@Component
class DpopReplayProtectionService(private val repository: DpopProofReplayRepository) {

    /**
     * [Propagation.REQUIRES_NEW] because this runs during argument resolution, before any
     * controller transaction exists - and because the duplicate-key violation must not poison a
     * surrounding transaction that the caller may still want to use.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun validateAndStore(thumbprint: String, jti: String?, expiresAt: Instant) {
        val key = sha256Hex("$thumbprint:$jti")
        try {
            repository.insert(key, expiresAt)
        } catch (_: DataIntegrityViolationException) {
            throw DpopValidationException("DPoP proof replay detected")
        }
    }

    /**
     * Sweeping is a scheduled job, not a side effect of validation. An entry is only useful until
     * the proof it describes would be rejected as too old anyway, so keeping them past that adds
     * nothing - but doing the sweep per request made every request pay for the whole table.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    @Transactional
    fun cleanupExpiredEntries() {
        repository.deleteExpired(Instant.now())
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
