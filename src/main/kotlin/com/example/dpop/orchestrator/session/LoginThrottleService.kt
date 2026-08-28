package com.example.dpop.orchestrator.session

import com.example.dpop.orchestrator.api.v1.OrchestratorException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

/**
 * Account-level throttle for AUTH-category tool attempts. Two entry points on purpose, because
 * the two kinds of AUTH tool must answer a lockout DIFFERENTLY:
 *
 * - A DEVICE_AUTH tool runs on a channel that already knows its account, so
 *   [ToolControllerSupport.beginActivation] calls [assertNotLocked] and the caller gets an
 *   explicit 423 - there is nothing left to leak, the account is established.
 * - A LOOKUP_AUTH tool resolves the account from a submitted e-mail. Surfacing a lockout there
 *   as its own error would hand back exactly the account-existence oracle that the tools'
 *   constant-shape failure is built to deny. Those callers ask [isLocked] and fold a `true` into
 *   their ordinary "E-Mail oder ... ungueltig" outcome instead (see the LOOKUP_AUTH controllers).
 *
 * Before this, lookup login was counted by nothing at all: both the check and the recording hung
 * on `channel.accountId`, which stays null for that whole flow until a proof SUCCEEDS.
 */
@Service
@Transactional
class LoginThrottleService(private val counter: AttemptCounter) {

    fun isLocked(accountId: Long): Boolean = counter.isLocked(ThrottleScope.ACCOUNT, key(accountId))

    fun assertNotLocked(accountId: Long) {
        if (isLocked(accountId)) {
            throw OrchestratorException.accountLocked(
                "Zu viele fehlgeschlagene Anmeldeversuche fuer diesen Account - bitte spaeter erneut versuchen"
            )
        }
    }

    fun recordFailure(accountId: Long) =
        counter.recordFailure(ThrottleScope.ACCOUNT, key(accountId), MAX_FAILURES, LOCKOUT_DURATION)

    /** Resets the throttle - called on every successful AUTH completion, not just after a prior lock. */
    fun recordSuccess(accountId: Long) = counter.reset(ThrottleScope.ACCOUNT, key(accountId))

    private fun key(accountId: Long) = accountId.toString()

    companion object {
        private const val MAX_FAILURES = 5
        private val LOCKOUT_DURATION: Duration = Duration.ofMinutes(15)
    }
}
