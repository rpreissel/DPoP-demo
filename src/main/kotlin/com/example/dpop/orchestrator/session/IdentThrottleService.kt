package com.example.dpop.orchestrator.session

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

/**
 * Person-level throttle for IDENT-category tool attempts.
 *
 * This closes an exception that used to be stated as a rule: "IDENT/ENROLL failures aren't a
 * brute-force target the same way (no credential guessed)". For ENROLL that holds. For IDENT it
 * does not - `ident-fsc` verifies exactly one secret (the Freischaltcode) against a KVNR, and a
 * hit means `Interpretation.AdoptIdentity`, i.e. creating OR taking over that person's account.
 * `ident-eid` guesses a PIN the same way.
 *
 * Keyed by personId rather than accountId because an identification runs before any account is
 * known - which is precisely why [LoginThrottleService] could never have covered it.
 *
 * Like the lookup case, a lock is NOT surfaced as its own error: the caller folds it into the
 * tool's ordinary failure, so the response cannot be used to test which KVNRs exist.
 */
@Service
@Transactional
class IdentThrottleService(private val counter: AttemptCounter) {

    fun isLocked(personId: Long): Boolean = counter.isLocked(ThrottleScope.PERSON, key(personId))

    fun recordFailure(personId: Long) =
        counter.recordFailure(ThrottleScope.PERSON, key(personId), MAX_FAILURES, LOCKOUT_DURATION)

    fun recordSuccess(personId: Long) = counter.reset(ThrottleScope.PERSON, key(personId))

    private fun key(personId: Long) = personId.toString()

    companion object {
        private const val MAX_FAILURES = 5
        private val LOCKOUT_DURATION: Duration = Duration.ofMinutes(15)
    }
}
