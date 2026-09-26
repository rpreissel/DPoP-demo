package com.example.dpop.orchestrator.session

import com.example.dpop.account.SignInLog

import com.example.dpop.texts.Text
import com.example.dpop.orchestrator.domain.OrchestratorException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Account-level throttle for AUTH-category tool attempts. Two entry points on purpose, because
 * the two kinds of AUTH tool must answer a lockout DIFFERENTLY:
 *
 * - A IDENTIFIED_AUTH tool runs on a channel that already knows its account, so
 *   [ToolControllerSupport.beginActivation] calls [assertNotLocked] and the caller gets an
 *   explicit 423 - there is nothing left to leak, the account is established.
 * - A LOOKUP_AUTH tool resolves the account from a submitted e-mail. Surfacing a lockout there
 *   as its own error would hand back exactly the account-existence oracle that the tools'
 *   constant-shape failure is built to deny. Those callers ask [isLocked] and fold a `true` into
 *   their ordinary "E-Mail oder ... ungueltig" outcome instead (see the LOOKUP_AUTH controllers).
 *
 * Both paths key on the resolved account rather than `channel.accountId`, which stays null
 * throughout a lookup login until a proof SUCCEEDS - and would therefore count nothing.
 */
@Service
@Transactional
class LoginThrottleService(private val counter: AttemptCounter, private val signInLog: SignInLog) {

    fun isLocked(accountId: Long): Boolean = counter.isLocked(ThrottleScope.ACCOUNT, key(accountId))

    fun assertNotLocked(accountId: Long) {
        if (isLocked(accountId)) {
            throw OrchestratorException.accountLocked(
                Text("Zu viele fehlgeschlagene Anmeldeversuche fuer diesen Account - bitte spaeter erneut versuchen")
            )
        }
    }

    /**
     * One failed proof of [accountId] with [method] on [channel] - counted, and written to the
     * account's sign-in log, together with the lockout if this very failure tripped it
     * (ADR-39, addendum). The one place every such failure passes, whichever channel it came from.
     */
    fun recordFailure(accountId: Long, channel: String?, method: String) {
        val lockedBefore = counter.lockedUntil(ThrottleScope.ACCOUNT, key(accountId))
        counter.recordFailure(ThrottleScope.ACCOUNT, key(accountId), MAX_FAILURES, LOCKOUT_DURATION)
        signInLog.signInFailed(accountId, channel, method)
        val lockedNow = counter.lockedUntil(ThrottleScope.ACCOUNT, key(accountId))
        if (lockedNow != null && lockedNow != lockedBefore && Instant.now().isBefore(lockedNow)) {
            signInLog.lockedOut(accountId, channel, lockedNow)
        }
    }

    /** Resets the throttle - called on every successful AUTH completion, not just after a prior lock. */
    fun recordSuccess(accountId: Long) = counter.reset(ThrottleScope.ACCOUNT, key(accountId))

    private fun key(accountId: Long) = accountId.toString()

    companion object {
        private const val MAX_FAILURES = 5
        private val LOCKOUT_DURATION: Duration = Duration.ofMinutes(15)
    }
}
