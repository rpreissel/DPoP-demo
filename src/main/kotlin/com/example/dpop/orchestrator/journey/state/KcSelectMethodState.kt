package com.example.dpop.orchestrator.journey.state

import com.example.dpop.texts.Text
import com.example.dpop.tool_spi.ToolId

/**
 * The single state of `KC_SELECT_METHOD` (docs/04-orchestrierung.md Abschnitt 3) - always offers
 * every kc-usable tool as one `selectMethod` step, re-offered (narrowed by [declined]) until
 * either a proof closes the gap or nothing is left.
 */
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
        override val offer: Offer,
        override val accountAlreadyKnown: Boolean
    ) : KcSelectMethodState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "auth"
        // Already signed in means the website asked for more (a step-up): the page must say why it
        // asks again - Keycloak shows the known e-mail address where the heading would be, so the
        // description carries the reason on its own.
        override val selectionTitle: Text get() =
            if (accountAlreadyKnown) Text("Erhöhte Sicherheit erforderlich") else Text("Anmeldeverfahren wählen")
        override val selectionDescription: Text get() =
            if (accountAlreadyKnown) {
                Text("Sie sind bereits angemeldet. Dieser Bereich verlangt aber mehr Sicherheit: Bestätigen Sie Ihre Anmeldung mit einem weiteren Verfahren.")
            } else {
                Text("Wählen Sie, wie Sie sich anmelden möchten.")
            }
        override val logDetail: Map<String, Any?> get() = mapOf("accountAlreadyKnown" to accountAlreadyKnown)
    }
}
