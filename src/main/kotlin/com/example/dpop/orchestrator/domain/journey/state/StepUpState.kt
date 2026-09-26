package com.example.dpop.orchestrator.domain.journey.state

import com.example.dpop.texts.Text
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.ToolId

sealed interface StepUpState : JourneyState {
    /** The goal of THIS run - distinct from the channel's durable `acrFloor`. */
    val targetAcr: AcrLevel

    companion object {
        /**
         * The seed a strategy hands [com.example.dpop.orchestrator.domain.journey.Transition.RequireSubJourney]
         * when it needs STEP_UP to run as its own precondition (docs/04-orchestrierung.md #6) - the
         * one place that knows how a fresh STEP_UP run is represented, so callers never construct
         * [Start] themselves.
         *
         * [reason] lets the CALLER explain, in its own words, why THIS run exists - STEP_UP itself
         * has no opinion (it is deliberately the one shared gate every caller reuses, docs/04-
         * orchestrierung.md #6), but the generic default ("die angeforderte Aktion...") reads as
         * coming from nowhere on a caller like `CONFIRM_PEER_LOGIN`'s COLD entry, where the user
         * never took any "Aktion" themselves - they just scanned a QR code (real user feedback this
         * closes). `null` keeps the generic wording for callers with nothing more specific to say.
         * A [Reason] rather than the words themselves: the state is persisted, and a text is not
         * state - its wording lives here, per caller, and changes without touching stored journeys.
         */
        fun forSubJourney(targetAcr: AcrLevel, startingAcr: AcrLevel, allowReIdentification: Boolean = true, reason: Reason? = null): StepUpState =
            Start(targetAcr, startingAcr, allowReIdentification, reason)
    }

    /** Why a caller needs this step-up, when it has more to say than the generic wording (see [forSubJourney]). */
    enum class Reason {
        /** CONFIRM_PEER_LOGIN's gate: a browser asks to log in, this session must first prove itself. */
        PEER_LOGIN
    }

    data class Start(
        override val targetAcr: AcrLevel,
        val startingAcr: AcrLevel,
        /**
         * Whether a dead end here (no active method reaches [targetAcr]) may fall back to offering
         * `RE_IDENTIFY` - false for [com.example.dpop.orchestrator.domain.AuthIntent.CONFIRM_PEER_LOGIN]'s
         * own gate (docs/04-orchestrierung.md, CONFIRM_PEER_LOGIN #1: a peer-approval must never let
         * someone acquire a fresh identity just to confirm someone else's login), true everywhere
         * else this sub-journey is used ([com.example.dpop.orchestrator.journey.strategy.DeleteAccountStrategy],
         * [com.example.dpop.orchestrator.journey.strategy.ManageAuthMethodsStrategy], a direct step-up trigger).
         *
         * Defaulting to `true` is deliberate, not just permissive: a re-identification is an
         * EQUALLY VALID path to the loa2/NIST-AAL2 threshold, not a lesser fallback only reached
         * once AUTH options are exhausted for lack of alternatives (docs/04-orchestrierung.md #8,
         * "loa2 ist... das Projekt-eigene Label für NIST-800-63B-AAL2"). It only has to be turned
         * off where offering it would be actively wrong (the peer-approval case above), never
         * because it's "worse" than an AUTH combination.
         */
        val allowReIdentification: Boolean = true,
        /** See [StepUpState.forSubJourney]'s own doc - carried through into [AuthChoice] once offered. */
        val reason: Reason? = null
    ) : StepUpState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "auth"
    }

    data class AuthChoice(
        override val targetAcr: AcrLevel,
        val startingAcr: AcrLevel,
        override val offer: Offer,
        val allowReIdentification: Boolean = true,
        /** See [StepUpState.forSubJourney]'s own doc. */
        val reason: Reason? = null,
        /**
         * True once this session already proved one AUTHENTICATOR-axis factor THIS run (real user
         * feedback: two back-to-back "Erhöhte Sicherheit erforderlich" screens with byte-identical
         * text, offering fewer methods the second time, read as the same request repeating/stuck -
         * not as "you gave one factor, now give a DIFFERENT one to complete the combination"). Set
         * once, at the point [com.example.dpop.orchestrator.journey.strategy.StepUpStrategy.offerAuth]
         * builds this state - never recomputed afterwards, so it stays accurate for exactly the
         * offer it was computed for.
         */
        val additionalFactorRound: Boolean = false
    ) : StepUpState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "auth"
        override val selectionTitle: Text get() = Text("Erhöhte Sicherheit erforderlich")
        override val selectionDescription: Text get() {
            val base = when (reason) {
                // The FIRST screen a missing-loa2 peer approval ever sees, so it carries the whole
                // context: what's happening, why this session must prove more, and that
                // "Abbrechen" is the way out (ConfirmPeerLoginStrategy).
                Reason.PEER_LOGIN -> Text(
                    "Ein Browser möchte sich mit Ihrem Konto anmelden. Um das zu bestätigen, muss " +
                        "diese Sitzung zunächst selbst ein höheres Sicherheitsniveau nachweisen. Brechen Sie ab, wenn Sie " +
                        "diesen Login nicht selbst ausgelöst haben."
                )
                null -> Text("Die angeforderte Aktion erfordert ein höheres Sicherheitsniveau. Bitte bestätigen Sie Ihre Identität mit einem weiteren Verfahren.")
            }
            return if (additionalFactorRound) {
                Text(
                    "{anlass} Das eben genutzte Verfahren zählt bereits. Wählen Sie jetzt eines anderer Art, " +
                        "zum Beispiel Passwort statt SMS.",
                    "anlass" to base
                )
            } else {
                base
            }
        }
    }
}
