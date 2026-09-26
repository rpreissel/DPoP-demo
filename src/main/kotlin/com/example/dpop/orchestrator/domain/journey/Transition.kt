package com.example.dpop.orchestrator.domain.journey

import com.example.dpop.orchestrator.journey.JourneyService
import com.example.dpop.texts.Text
import com.example.dpop.orchestrator.domain.journey.state.JourneyState
import com.example.dpop.orchestrator.domain.journey.state.OfferingState
import com.example.dpop.orchestrator.domain.ChannelState
import com.example.dpop.orchestrator.domain.AuthIntent

/**
 * What should happen next. Deliberately NOT a `Next` - the skip-if-single-candidate rule and the
 * routing derivation live once in [JourneyService], not once per intent.
 *
 * There is no separate "offer these tools" variant: a state already carries what it offers
 * ([JourneyState.activatable]), so [To] to that state IS the offer.
 */
sealed interface Transition {
    /** Move the journey to [state]; what it now offers is read straight off that state. */
    data class To(val state: JourneyState) : Transition

    /**
     * Run [intent] first, seeded at [seedWith] (the requesting strategy builds this itself via
     * that intent's own state's companion factory, e.g. `StepUpState.forSubJourney(...)` - same
     * idiom as [resumeWith] already builds ITS OWN journey's continuation state directly), then
     * resume this journey at [resumeWith] once it finishes.
     */
    data class RequireSubJourney(
        val intent: AuthIntent,
        val seedWith: JourneyState,
        val resumeWith: JourneyState
    ) : Transition {
        init {
            // [resumeWith] is persisted and only reactivated once the whole sub-journey has run -
            // minutes later, with new evidence, possibly a different account and different active
            // methods. A candidate list frozen into it would be re-offered as if it were current;
            // worse, the sub-journey exists precisely BECAUSE the situation was insufficient, so
            // its result is the one thing that must be re-read. Resume at a state that recomputes
            // (a Start-like one), never at an offer.
            //
            // Enforced rather than documented: "every caller gets it right" is not a property a
            // safety rule may rest on, however true it happens to be right now.
            check(resumeWith !is OfferingState) {
                "resumeWith must not be an OfferingState (${resumeWith::class.simpleName}): a sub-journey's " +
                    "whole point is that the situation changed, so the offer has to be recomputed on return"
            }
        }
    }

    /** Goal reached: consume the journey, the channel becomes AUTHENTICATED. */
    data object Authenticated : Transition

    /**
     * The user gave up (abandoned the last thing this journey could offer). Distinct from
     * [Abort]: nothing went wrong, so this ends like an explicit cancel - back to the channel's
     * login status before the journey ([ChannelState.isLoggedIn]), with a fresh start offered
     * afterwards - rather than as a 410.
     */
    data object Cancel : Transition

    /**
     * Run [action], then resume the journey at [resumeState] with [JourneyEvent.ActionCompleted]
     * once it has - the state a completed tool outcome or a strategy's own action (deactivating a
     * method, linking a device, deleting an account) continues at afterward.
     */
    data class Perform(val action: Action, val resumeState: JourneyState) : Transition

    /**
     * Confirmed logout: ends the channel for good. The journey is consumed, authContext
     * discarded, channel becomes LOGGED_OUT. Also the natural resume target after
     * [Action.DeleteAccount].
     */
    data object Logout : Transition

    /** No way forward at all. Ends the journey with 410 - never a mere "no candidates left". */
    data class Abort(val reason: Text) : Transition
}
