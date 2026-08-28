package com.example.dpop.orchestrator.session

import com.example.dpop.orchestrator.api.v1.OrchestratorException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

/**
 * Rate limit on channel creation, keyed by the caller's DPoP binding key.
 *
 * The multiplier the other two throttles cannot see: `AuthJourney.attemptBudget` bounds guesses
 * within ONE journey, but a fresh journey costs a single `POST .../app/channels` with a
 * self-generated key pair - which is free. Without this, every per-journey budget in the system
 * is a formality.
 *
 * A binding key is not an identity and costs nothing to rotate, so this is a speed bump, not an
 * authorization check: it makes a brute-force run expensive enough to be worth noticing, while
 * the real bounds stay [LoginThrottleService] and [IdentThrottleService], which key on the thing
 * actually under attack. Counted per rolling window (every creation counts, not just failures),
 * and answered with 429 rather than a lock, because there is no secret to protect here.
 */
@Service
@Transactional
class ChannelCreationThrottleService(private val counter: AttemptCounter) {

    fun recordAndAssertWithinBudget(bindingKeyRef: String) {
        val withinBudget = counter.recordWindowedAttempt(
            ThrottleScope.BINDING_KEY, bindingKeyRef, MAX_PER_WINDOW, WINDOW
        )
        if (!withinBudget) {
            throw OrchestratorException.tooManyRequests(
                "Zu viele Kanaleroeffnungen fuer dieses Geraet - bitte spaeter erneut versuchen"
            )
        }
    }

    companion object {
        private const val MAX_PER_WINDOW = 20
        private val WINDOW: Duration = Duration.ofMinutes(5)
    }
}
