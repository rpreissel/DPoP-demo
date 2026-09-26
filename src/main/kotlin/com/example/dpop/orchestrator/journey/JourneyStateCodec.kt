package com.example.dpop.orchestrator.journey

import com.example.dpop.orchestrator.domain.journey.state.ConfirmPeerLoginState
import com.example.dpop.orchestrator.domain.journey.state.DeleteAccountState
import com.example.dpop.orchestrator.domain.journey.state.FastAccessState
import com.example.dpop.orchestrator.domain.journey.state.JourneyState
import com.example.dpop.orchestrator.domain.journey.state.KcSelectMethodState
import com.example.dpop.orchestrator.domain.journey.state.LookupLoginState
import com.example.dpop.orchestrator.domain.journey.state.LogoutState
import com.example.dpop.orchestrator.domain.journey.state.ManageAuthMethodsState
import com.example.dpop.orchestrator.domain.journey.state.ReIdentifyState
import com.example.dpop.orchestrator.domain.journey.state.RegisterEnrollFirstState
import com.example.dpop.orchestrator.domain.journey.state.RegisterState
import com.example.dpop.orchestrator.domain.journey.state.StepUpState
import org.springframework.stereotype.Component
import tools.jackson.databind.exc.InvalidTypeIdException
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.databind.jsontype.NamedType
import com.fasterxml.jackson.annotation.JsonTypeInfo
import kotlin.reflect.KClass
import com.example.dpop.orchestrator.domain.AuthIntent

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
 * all, only inside the payload's type name. Safe because their discriminator names are already
 * disjoint (`EnrollFirst*` vs. the ident-first names) - never ambiguous which one a given payload
 * actually is.
 *
 * The states themselves carry no serialization (docs/adr/ADR-040-fachkern-im-paket-domain.md): the type
 * name is the state's simple class name, written as `@t`, and the subtypes are derived here from
 * the sealed hierarchies. `JourneyStateCodecTest` pins every name, so a renamed state - whose
 * persisted journeys would no longer read - fails a test instead of a running journey.
 */
@Component
class JourneyStateCodec {

    private val mapper = jacksonMapperBuilder()
        .addMixIn(JourneyState::class.java, PersistedTypeName::class.java)
        .registerSubtypes(*STATE_ROOTS.flatMap(::concreteStates).distinct().map { NamedType(it.java, it.simpleName) }.toTypedArray())
        .build()

    /** `@t` = the simple class name - for every state, from one place instead of on each root. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
    private interface PersistedTypeName

    fun write(journey: AuthJourney, state: JourneyState) {
        journey.stateType = state.javaClass.simpleName
        journey.state = mapper.writeValueAsString(state)
    }

    fun read(journey: AuthJourney): JourneyState {
        val json = checkNotNull(journey.state) { "Journey ${journey.journeyId} has no state" }
        val intent = journey.requireIntent()
        if (intent == AuthIntent.REGISTER) {
            return try {
                mapper.readValue(json, RegisterState::class.java)
            } catch (e: InvalidTypeIdException) {
                mapper.readValue(json, RegisterEnrollFirstState::class.java)
            }
        }
        return mapper.readValue(json, rootOf(intent))
    }

    companion object {
        /** Every sealed root a journey's state is read back as - one per intent, two for REGISTER. */
        val STATE_ROOTS: List<KClass<out JourneyState>> = listOf(
            FastAccessState::class, RegisterState::class, RegisterEnrollFirstState::class, LookupLoginState::class,
            KcSelectMethodState::class, StepUpState::class, ManageAuthMethodsState::class,
            ConfirmPeerLoginState::class, DeleteAccountState::class, LogoutState::class, ReIdentifyState::class,
        )

        /** The instantiable states under [root], through nested sealed levels. */
        fun concreteStates(root: KClass<out JourneyState>): List<KClass<out JourneyState>> =
            if (root.isSealed) root.sealedSubclasses.flatMap(::concreteStates) else listOf(root)
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
