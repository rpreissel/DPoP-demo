package com.example.dpop.orchestrator.journey.state

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * The single state of `KC_SELECT_METHOD` (docs/04-orchestrierung.md Abschnitt 3) - always offers
 * every kc-usable tool as one `selectMethod` step, re-offered (narrowed by [declined]) until
 * either a proof closes the gap or nothing is left.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = KcSelectMethodState.SelectMethod::class, name = "SelectMethod")
)
sealed interface KcSelectMethodState : JourneyState {

    /**
     * Whether the account was already known when this offer was built (Web-Kanal step-up,
     * docs/04-orchestrierung.md Abschnitt 3) - decides how a `Completed.Authenticated` outcome
     * is interpreted (trust the outcome's own account vs. only the already-bound one), the same
     * distinction `LookupLoginStrategy` makes via its own state shape - `interpret` never has
     * [com.example.dpop.orchestrator.journey.JourneyContext] to ask directly. Declared on the
     * sealed interface itself (not just the one variant below) so `interpret` can read it without
     * a downcast, the same way [com.example.dpop.orchestrator.journey.state.StepUpState.targetAcr] is.
     */
    val accountAlreadyKnown: Boolean

    data class SelectMethod(
        override val offered: List<String>,
        override val accountAlreadyKnown: Boolean,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : KcSelectMethodState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "auth"
        override val selectionTitle: String get() = "Anmeldeverfahren wählen"
        override val logDetail: Map<String, Any?> get() = mapOf("accountAlreadyKnown" to accountAlreadyKnown)
    }
}
