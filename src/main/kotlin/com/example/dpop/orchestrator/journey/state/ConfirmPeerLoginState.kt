package com.example.dpop.orchestrator.journey.state

import com.example.dpop.texts.Text
import com.example.dpop.tool_spi.ToolId
import com.example.dpop.orchestrator.kernel.AuthIntent

/**
 * Approve or decline a WEB-channel login waiting on this account's peer approval
 * (docs/04-orchestrierung.md, CONFIRM_PEER_LOGIN). Reached two ways at once
 * ([AuthIntent.CONFIRM_PEER_LOGIN]'s own doc) - a cold entry and an already-authenticated channel
 * both land on [Requested] and share the exact same gate from there, so there is no separate
 * "Start" state: the only thing that differs between the two entries is whether an account is
 * already known, and [Requested] itself decides what that means.
 *
 * [startedAuthenticated] travels through every state of a single run (set once, at entry, never
 * recomputed) - it is what [Confirming] uses to decide whether finishing should ask about logging
 * back out again ([OfferLogout]): a channel that was only ever authenticated FOR this one
 * confirmation has no reason to stay logged in afterwards, unlike one that already was.
 */
sealed interface ConfirmPeerLoginState : JourneyState {

    /**
     * The wish, before the loa2 gate has been evaluated - both the entry-intent's initial state
     * AND the state the journey is parked in while a STEP_UP sub-journey runs (same reasoning as
     * [ManageAuthMethodsState.AddRequested]). On a cold entry, `ctx.account == null` means no
     * `DeviceAccountLink` exists yet - the strategy aborts right here rather than ever falling into
     * identification/registration.
     *
     * Deliberately no separate "do you want to confirm this?" gate ahead of the security checks
     * below: an extra tap before the checks even start is net friction for no gain, since the
     * STEP_UP screen a missing-loa2 case lands on already both explains WHY it's asking and offers
     * "Abbrechen" as the way out. Instead, [StepUpStrategy]'s own [reason][com.example.dpop.orchestrator.journey.
     * state.StepUpState.forSubJourney] text carries that context on the FIRST screen actually
     * shown - see [ConfirmPeerLoginStrategy]'s `STEP_UP_REASON`.
     */
    data class Requested(val startedAuthenticated: Boolean) : ConfirmPeerLoginState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "enrollment"
    }

    /**
     * Re-prove any one active factor, fresh, right now - reached ONLY when the channel already
     * satisfied loa2 on its own (evidence of unknown age), never when that loa2 evidence was just
     * freshly produced by this journey's own step-up (`ConfirmPeerLoginStrategy`'s own doc). Same
     * reasoning and shape as [com.example.dpop.orchestrator.journey.state.DeleteAccountState.ConfirmationRequired]:
     * an already-authenticated but possibly hijacked session must not be able to vouch for a
     * foreign login on the strength of old evidence alone.
     */
    data class ConfirmationRequired(
        val startedAuthenticated: Boolean,
        override val offer: Offer
    ) : ConfirmPeerLoginState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "auth"
        override val selectionTitle: Text get() = Text("Web-Login bestätigen – Identität erneut bestätigen")
        override val selectionDescription: Text?
            get() = Text("Bevor Sie den Web-Login bestätigen, weisen Sie sich noch einmal aus.")
    }

    /**
     * loa2 satisfied - `confirm-qr-login` is the one and only candidate. Modeled as an
     * [OfferingState] with a single-element [offered] purely to reuse the existing
     * skip-the-selection-screen machinery ([JourneyService.nextFor]), not because there is
     * actually a choice.
     */
    data class Confirming(
        val startedAuthenticated: Boolean,
        // The offer here is fixed (this state exists to run exactly confirm-qr-login), so it is a
        // default rather than something a caller supplies.
        override val offer: Offer = Offer(listOf(ToolId("confirm-qr-login")))
    ) : ConfirmPeerLoginState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "auth"
        override val selectionTitle: Text get() = Text("Web-Login bestätigen")
        override val selectionDescription: Text
            get() = Text("Ein Browser möchte sich mit Ihrem Konto anmelden. Bestätigen Sie das nur, wenn Sie diesen Login selbst ausgelöst haben.")
    }

    /**
     * Only reached when [startedAuthenticated] was false: this channel had no session of its own
     * before it authenticated solely to perform this one confirmation. Asking rather than either
     * silently logging out (surprising if the user actually wanted to stay) or silently staying in
     * (a channel nobody asked to keep logged in) - same reasoning as
     * [LookupLoginState.OfferBinding] asking rather than assuming.
     */
    data object OfferLogout : ConfirmPeerLoginState, AnswerableState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val question: Question
            get() = Question.Confirm(
                title = Text("Jetzt abmelden?"),
                description = Text("Dieses Gerät war vor der Bestätigung nicht angemeldet - nur für diese eine Bestätigung wurde es kurz angemeldet. Jetzt wieder abmelden, oder angemeldet bleiben?"),
                confirmLabel = Text("Abmelden"),
                cancelLabel = Text("Angemeldet bleiben"),
                destructive = false
            )
    }
}
