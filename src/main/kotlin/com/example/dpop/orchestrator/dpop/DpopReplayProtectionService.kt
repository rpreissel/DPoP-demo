package com.example.dpop.orchestrator.dpop

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Single-use enforcement for DPoP and device proofs.
 *
 * Backed by a table rather than the `ConcurrentHashMap` this used to be. That map had three
 * problems, all of which made the guarantee weaker than it looked: it was emptied by every
 * restart, it was per-instance (so behind more than one replica a proof could simply be replayed
 * against a different node), and it was swept in full on EVERY validation - an O(n) scan over a
 * map whose size the caller controls, since any well-formed proof with a fresh `jti` earns an
 * entry for the length of the acceptance window.
 *
 * The insert itself is the check: `proof_key` is the primary key, so a duplicate raises rather
 * than needing a read-then-write that two concurrent replays could both pass.
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
        val key = "$thumbprint:$jti"
        try {
            repository.saveAndFlush(DpopProofReplay(key, expiresAt))
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
}
