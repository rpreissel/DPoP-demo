package com.example.dpop.orchestrator.journey

import com.example.dpop.orchestrator.journey.state.DeleteAccountState
import com.example.dpop.orchestrator.journey.state.FastAccessState
import com.example.dpop.orchestrator.journey.state.JourneyState
import com.example.dpop.orchestrator.journey.state.LookupLoginState
import com.example.dpop.orchestrator.journey.state.ManageAuthMethodsState
import com.example.dpop.orchestrator.journey.state.StepUpState
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Persists a [JourneyState] as `(stateType, state)` on the [AuthJourney] row.
 *
 * The discriminator stays its own column so journeys remain queryable by position ("how many runs
 * are stuck on the enrolment state?") without parsing JSON. The attributes travel as JSON because
 * they differ per state - giving each of them a column would mean a wide table of mostly-null
 * fields, which is exactly the shapeless routing state this model replaced.
 *
 * Which sealed root to read back is decided by the journey's [AuthIntent], not guessed from the
 * payload: two intents may legitimately have a state of the same name.
 */
@Component
class JourneyStateCodec {

    private val mapper = jacksonObjectMapper()

    fun write(journey: AuthJourney, state: JourneyState) {
        journey.stateType = state.javaClass.simpleName
        journey.state = mapper.writeValueAsString(state)
    }

    fun read(journey: AuthJourney): JourneyState {
        val json = checkNotNull(journey.state) { "Journey ${journey.journeyId} has no state" }
        val intent = checkNotNull(journey.intent) { "Journey ${journey.journeyId} has no intent" }
        return mapper.readValue(json, rootOf(intent))
    }

    private fun rootOf(intent: AuthIntent): Class<out JourneyState> = when (intent) {
        // REGISTER shares FAST's states on purpose: its states ARE FAST's from the identification
        // one on, and only the entry point differs (see RegisterStrategy).
        AuthIntent.FAST_ACCESS, AuthIntent.REGISTER -> FastAccessState::class.java
        AuthIntent.LOOKUP_LOGIN -> LookupLoginState::class.java
        AuthIntent.STEP_UP -> StepUpState::class.java
        AuthIntent.MANAGE_AUTH_METHODS -> ManageAuthMethodsState::class.java
        AuthIntent.DELETE_ACCOUNT -> DeleteAccountState::class.java
    }
}
