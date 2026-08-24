package com.example.dpop.orchestrator.session

import com.example.dpop.orchestrator.api.v1.OrchestratorException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Account-level throttle for AUTH-category tool attempts (see LoginAttemptThrottle). Checked at
 * activation (ToolControllerSupport.beginActivation) - that is the actual bypass vector a
 * per-ToolSession retry counter can't close, since activation always mints a fresh one.
 */
@Service
@Transactional
class LoginThrottleService(private val repository: LoginAttemptThrottleRepository) {

    fun assertNotLocked(accountId: Long) {
        val throttle = repository.findByIdOrNull(accountId) ?: return
        val lockedUntil = throttle.lockedUntil ?: return
        if (Instant.now().isBefore(lockedUntil)) {
            throw OrchestratorException.accountLocked(
                "Zu viele fehlgeschlagene Anmeldeversuche fuer diesen Account - bitte spaeter erneut versuchen"
            )
        }
    }

    fun recordFailure(accountId: Long) {
        val throttle = repository.findByIdOrNull(accountId) ?: LoginAttemptThrottle(accountId = accountId)
        throttle.failedCount += 1
        if (throttle.failedCount >= MAX_FAILURES) {
            throttle.lockedUntil = Instant.now().plus(LOCKOUT_DURATION)
        }
        throttle.updatedAt = Instant.now()
        repository.save(throttle)
    }

    /** Resets the throttle - called on every successful AUTH completion, not just after a prior lock. */
    fun recordSuccess(accountId: Long) {
        val throttle = repository.findByIdOrNull(accountId) ?: return
        if (throttle.failedCount == 0 && throttle.lockedUntil == null) return
        throttle.failedCount = 0
        throttle.lockedUntil = null
        throttle.updatedAt = Instant.now()
        repository.save(throttle)
    }

    companion object {
        private const val MAX_FAILURES = 5
        private val LOCKOUT_DURATION: Duration = Duration.ofMinutes(15)
    }
}
