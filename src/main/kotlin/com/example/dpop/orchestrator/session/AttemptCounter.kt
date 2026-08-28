package com.example.dpop.orchestrator.session

import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * The counting/locking mechanics shared by the three throttle services - the optional base UNDER
 * the named APIs, never a generic entry point callers reach for directly
 * (docs/08-projektrahmen.md A11). Nothing here decides WHAT is worth counting or WHERE the limit
 * sits; every such number lives in the service that owns the scope.
 */
@Component
@Transactional
class AttemptCounter(private val repository: AttemptThrottleRepository) {

    fun isLocked(scope: ThrottleScope, subject: String): Boolean {
        val throttle = repository.findByIdOrNull(AttemptThrottleId(scope, subject)) ?: return false
        val lockedUntil = throttle.lockedUntil ?: return false
        return Instant.now().isBefore(lockedUntil)
    }

    fun recordFailure(scope: ThrottleScope, subject: String, maxFailures: Int, lockout: Duration) {
        val id = AttemptThrottleId(scope, subject)
        val throttle = repository.findByIdOrNull(id) ?: AttemptThrottle(id)
        throttle.failedCount += 1
        if (throttle.failedCount >= maxFailures) {
            throttle.lockedUntil = Instant.now().plus(lockout)
        }
        throttle.updatedAt = Instant.now()
        repository.save(throttle)
    }

    fun reset(scope: ThrottleScope, subject: String) {
        val throttle = repository.findByIdOrNull(AttemptThrottleId(scope, subject)) ?: return
        if (throttle.failedCount == 0 && throttle.lockedUntil == null) return
        throttle.failedCount = 0
        throttle.lockedUntil = null
        throttle.updatedAt = Instant.now()
        repository.save(throttle)
    }

    /**
     * Rolling-window variant for scopes where every attempt counts, not just the failed ones:
     * the counter restarts once [window] has passed since the last one.
     *
     * @return true when this attempt is still within budget, false once it exceeds [maxPerWindow].
     */
    fun recordWindowedAttempt(
        scope: ThrottleScope,
        subject: String,
        maxPerWindow: Int,
        window: Duration
    ): Boolean {
        val id = AttemptThrottleId(scope, subject)
        val now = Instant.now()
        val throttle = repository.findByIdOrNull(id) ?: AttemptThrottle(id)
        val windowStart = throttle.updatedAt ?: now
        if (windowStart.isBefore(now.minus(window))) {
            throttle.failedCount = 0
        }
        throttle.failedCount += 1
        throttle.updatedAt = now
        repository.save(throttle)
        return throttle.failedCount <= maxPerWindow
    }
}
