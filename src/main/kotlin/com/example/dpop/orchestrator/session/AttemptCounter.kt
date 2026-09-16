package com.example.dpop.orchestrator.session

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * The counting/locking mechanics shared by the three throttle services - the optional base UNDER
 * the named APIs, never a generic entry point callers reach for directly
 * (docs/08-projektrahmen.md A11). Nothing here decides WHAT is worth counting or WHERE the limit
 * sits; every such number lives in the service that owns the scope.
 *
 * Every mutation goes through a single atomic statement on the counter row
 * ([AttemptThrottleRepository]), never a load-modify-save. That is a security property, not a
 * performance one: with a read-then-write, N requests fired at once all read the same
 * pre-increment count, all decide independently that they are still within budget and all write
 * back the same value - so the effective budget becomes "limit x parallelism" and the lockout can
 * be skipped over entirely. Optimistic locking would not fix it either (a failed writer would
 * simply not be counted, which is the attacker's goal here); the increment has to happen
 * IN the database, under the row lock the `update` itself takes.
 *
 * A missing counter row is handled by "update first, create only if it matched nothing, then
 * update again" ([AttemptThrottleRowInitializer]). That ordering keeps the steady state - the row
 * exists - at exactly ONE statement; only the first ever attempt for a subject pays for the extra
 * transaction.
 */
@Component
@Transactional
class AttemptCounter(
    private val repository: AttemptThrottleRepository,
    private val rowInitializer: AttemptThrottleRowInitializer
) {

    fun isLocked(scope: ThrottleScope, subject: String): Boolean {
        val lockedUntil = repository.findLockedUntil(scope, subject) ?: return false
        return Instant.now().isBefore(lockedUntil)
    }

    fun recordFailure(scope: ThrottleScope, subject: String, maxFailures: Int, lockout: Duration) {
        val now = Instant.now()
        val lockUntil = now.plus(lockout)
        if (repository.incrementFailure(scope, subject, maxFailures, lockUntil, now) == 0) {
            rowInitializer.createIfAbsent(scope, subject)
            repository.incrementFailure(scope, subject, maxFailures, lockUntil, now)
        }
    }

    fun reset(scope: ThrottleScope, subject: String) {
        repository.resetCounter(scope, subject, Instant.now())
    }

    /**
     * Rolling-window variant for scopes where every attempt counts, not just the failed ones:
     * the counter restarts once [window] has passed since the last one.
     *
     * The read-back after the atomic increment is safe despite being a second statement: the
     * `update` already holds this row's write lock for the rest of the transaction, so no
     * concurrent attempt can change the value between the two - and each caller reads back its
     * OWN increment's result, which is what the budget decision must be based on.
     *
     * @return true when this attempt is still within budget, false once it exceeds [maxPerWindow].
     */
    fun recordWindowedAttempt(
        scope: ThrottleScope,
        subject: String,
        maxPerWindow: Int,
        window: Duration
    ): Boolean {
        val now = Instant.now()
        val windowStart = now.minus(window)
        if (repository.incrementWithinWindow(scope, subject, windowStart, now) == 0) {
            rowInitializer.createIfAbsent(scope, subject)
            repository.incrementWithinWindow(scope, subject, windowStart, now)
        }
        // Fails closed: a counter that cannot be read cannot be shown to be within budget.
        val count = repository.findFailedCount(scope, subject) ?: return false
        return count <= maxPerWindow
    }
}
