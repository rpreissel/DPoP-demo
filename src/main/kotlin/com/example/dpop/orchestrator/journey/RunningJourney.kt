package com.example.dpop.orchestrator.journey

import com.example.dpop.orchestrator.kernel.AuthIntent
import java.util.UUID

/**
 * A journey that was running ([JourneyLifecycle.STARTED], not expired) when it was looked up - the
 * only form in which [JourneyService] lets anyone outside the machine act on a journey
 * (docs/invarianten.md I-2, review 2026-09 fahrplan Phase D step 18).
 *
 * The private constructor is the point: [of] is the one way to get one, and it refuses a finished,
 * suspended or expired journey. So "a tool result reaches a consumed journey" (S-1: replaying the
 * auth-sms PATCH after a logout) is no longer a check every entry point has to remember - there is
 * simply no value to call [JourneyService.applyOutcome] with.
 *
 * It is a witness for the moment of the lookup, not a live view: the transition it is handed to
 * may end the journey. Callers look it up once per request and do not keep it.
 */
class RunningJourney private constructor(internal val entity: AuthJourney) {
    val journeyId: UUID get() = checkNotNull(entity.journeyId)
    val channelSessionId: UUID get() = checkNotNull(entity.channelSessionId)
    val intent: AuthIntent get() = checkNotNull(entity.intent)

    companion object {
        fun of(journey: AuthJourney): RunningJourney? =
            journey.takeIf { it.lifecycle == JourneyLifecycle.STARTED && !it.isExpired }?.let(::RunningJourney)
    }
}
