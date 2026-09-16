package com.example.dpop.orchestrator.session

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Creates a missing counter row for [AttemptCounter], in its own transaction and nowhere else.
 *
 * Its own bean rather than a private method on [AttemptCounter], for two reasons that both matter:
 * a `REQUIRES_NEW` method called from within the same bean would bypass the Spring proxy and
 * silently run in the CALLER's transaction; and the duplicate-key violation that a concurrent
 * creator provokes must not poison that caller's transaction (on PostgreSQL a failed statement
 * aborts the whole transaction, so catching the exception in place would not be enough). The same
 * reasoning `DpopReplayProtectionService` already follows.
 *
 * Deliberately not an `INSERT ... ON CONFLICT DO NOTHING`: H2 2.x rejects that syntax outside
 * PostgreSQL compatibility mode, and a vendor-specific upsert in the throttle path would tie a
 * security mechanism to one database. The counted `update` stays the atomic part; this only ever
 * materializes a row at 0, which is why committing it separately is harmless - a row created here
 * whose caller then rolls back is an unused counter at zero, not a lost or a double count.
 */
@Component
class AttemptThrottleRowInitializer(private val repository: AttemptThrottleRepository) {

    /**
     * Idempotent and race-tolerant: the loser of a concurrent creation sees the unique violation
     * its rival caused and treats it as success, because the only thing the caller needs is that
     * the row EXISTS before it retries its increment.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun createIfAbsent(scope: ThrottleScope, subject: String) {
        val id = AttemptThrottleId(scope, subject)
        if (repository.existsById(id)) return
        try {
            repository.saveAndFlush(AttemptThrottle(id))
        } catch (_: DataIntegrityViolationException) {
            // A concurrent request created it first - exactly the outcome this method wants.
        }
    }
}
