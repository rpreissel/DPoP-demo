package com.example.dpop.orchestrator.journey

import com.example.dpop.orchestrator.journey.state.ConfirmPeerLoginState
import com.example.dpop.orchestrator.journey.state.DeleteAccountState
import com.example.dpop.orchestrator.journey.state.FastAccessState
import com.example.dpop.orchestrator.journey.state.JourneyState
import com.example.dpop.orchestrator.journey.state.KcSelectMethodState
import com.example.dpop.orchestrator.journey.state.LookupLoginState
import com.example.dpop.orchestrator.journey.state.LogoutState
import com.example.dpop.orchestrator.journey.state.ManageAuthMethodsState
import com.example.dpop.orchestrator.journey.state.ReIdentifyState
import com.example.dpop.orchestrator.journey.state.RegisterEnrollFirstState
import com.example.dpop.orchestrator.journey.state.RegisterState
import com.example.dpop.orchestrator.journey.state.StepUpState
import org.springframework.stereotype.Component
import tools.jackson.databind.exc.InvalidTypeIdException
import tools.jackson.module.kotlin.jacksonObjectMapper
import com.example.dpop.orchestrator.kernel.AuthIntent

/**
 * Persists a [JourneyState] as `(stateType, state)` on the [AuthJourney] row.
 *
 * The discriminator stays its own column so journeys remain queryable by position ("how many runs
 * are stuck on the enrolment state?") without parsing JSON. The attributes travel as JSON because
 * they differ per state - giving each of them a column would mean a wide table of mostly-null
 * fields, which is exactly the shapeless routing state this model replaced.
 *
 * Which sealed root to read back is decided by the journey's [AuthIntent], not guessed from the
 * payload: two intents may legitimately have a state of the same name. [AuthIntent.REGISTER] is the
 * one deliberate exception (`RegisterDispatchStrategy`'s two fully independent state hierarchies,
 * [RegisterState]/[RegisterEnrollFirstState] - see the latter's own doc) - [read] tries both roots
 * rather than picking one, since which of the two a given journey actually is isn't known here at
 * all, only inside the states' own `@JsonTypeInfo` payload. Safe because their discriminator names
 * are already disjoint (`EnrollFirst*` vs. the ident-first names) - never ambiguous which one a
 * given payload actually is.
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
        if (intent == AuthIntent.REGISTER) {
            return try {
                mapper.readValue(json, RegisterState::class.java)
            } catch (e: InvalidTypeIdException) {
                mapper.readValue(json, RegisterEnrollFirstState::class.java)
            }
        }
        return mapper.readValue(json, rootOf(intent))
    }

    private fun rootOf(intent: AuthIntent): Class<out JourneyState> = when (intent) {
        AuthIntent.FAST_ACCESS -> FastAccessState::class.java
        AuthIntent.REGISTER -> error("REGISTER is handled separately in read() - see its own doc")
        AuthIntent.LOOKUP_LOGIN -> LookupLoginState::class.java
        AuthIntent.KC_SELECT_METHOD -> KcSelectMethodState::class.java
        AuthIntent.STEP_UP -> StepUpState::class.java
        AuthIntent.MANAGE_AUTH_METHODS -> ManageAuthMethodsState::class.java
        AuthIntent.CONFIRM_PEER_LOGIN -> ConfirmPeerLoginState::class.java
        AuthIntent.DELETE_ACCOUNT -> DeleteAccountState::class.java
        AuthIntent.LOGOUT -> LogoutState::class.java
        AuthIntent.RE_IDENTIFY -> ReIdentifyState::class.java
    }
}
