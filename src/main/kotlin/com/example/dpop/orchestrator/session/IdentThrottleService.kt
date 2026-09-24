package com.example.dpop.orchestrator.session

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

/**
 * Person-level throttle for IDENT-category tool attempts.
 *
 * Unlike ENROLL ("no credential guessed"), IDENT failures ARE a brute-force target:
 * `ident-fsc` verifies exactly one secret (the Freischaltcode) against a KVNR, and a
 * hit means `Action.RecordIdentification`, i.e. creating OR taking over that person's account.
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

    fun isLocked(personId: String): Boolean = counter.isLocked(ThrottleScope.PERSON, key(personId))

    fun recordFailure(personId: String) =
        counter.recordFailure(ThrottleScope.PERSON, key(personId), MAX_FAILURES, LOCKOUT_DURATION)

    fun recordSuccess(personId: String) = counter.reset(ThrottleScope.PERSON, key(personId))

    private fun key(personId: String) = personId

    companion object {
        private const val MAX_FAILURES = 5
        private val LOCKOUT_DURATION: Duration = Duration.ofMinutes(15)
    }
}
