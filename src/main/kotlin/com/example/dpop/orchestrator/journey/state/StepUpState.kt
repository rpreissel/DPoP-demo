package com.example.dpop.orchestrator.journey.state

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = StepUpState.Start::class, name = "Start"),
    JsonSubTypes.Type(value = StepUpState.AuthChoice::class, name = "AuthChoice")
)
sealed interface StepUpState : JourneyState {
    /** The goal of THIS run - distinct from the channel's durable `acrFloor`. */
    val targetAcr: String

    companion object {
        /**
         * The seed a strategy hands [com.example.dpop.orchestrator.journey.Transition.RequireSubJourney]
         * when it needs STEP_UP to run as its own precondition (docs/04-orchestrierung.md #6) - the
         * one place that knows how a fresh STEP_UP run is represented, so callers never construct
         * [Start] themselves.
         */
        fun forSubJourney(targetAcr: String, startingAcr: String, allowReIdentification: Boolean = true): StepUpState =
            Start(targetAcr, startingAcr, allowReIdentification)
    }

    data class Start(
        override val targetAcr: String,
        val startingAcr: String,
        /**
         * Whether a dead end here (no active method reaches [targetAcr]) may fall back to offering
         * `RE_IDENTIFY` - false for [com.example.dpop.orchestrator.journey.AuthIntent.CONFIRM_PEER_LOGIN]'s
         * own gate (docs/04-orchestrierung.md, CONFIRM_PEER_LOGIN #1: a peer-approval must never let
         * someone acquire a fresh identity just to confirm someone else's login), true everywhere
         * else this sub-journey is used ([com.example.dpop.orchestrator.journey.strategy.DeleteAccountStrategy],
         * [com.example.dpop.orchestrator.journey.strategy.ManageAuthMethodsStrategy], a direct step-up trigger).
         */
        val allowReIdentification: Boolean = true
    ) : StepUpState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<String>): Set<String> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "auth"
    }

    data class AuthChoice(
        override val targetAcr: String,
        val startingAcr: String,
        override val offered: List<String>,
        val allowReIdentification: Boolean = true,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : StepUpState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "auth"
        override val selectionTitle: String get() = "Erhöhte Sicherheit erforderlich"
        override val selectionDescription: String get() = "Die angeforderte Aktion erfordert ein höheres Sicherheitsniveau. Bitte bestätigen Sie Ihre Identität mit einem weiteren Verfahren."
    }
}
