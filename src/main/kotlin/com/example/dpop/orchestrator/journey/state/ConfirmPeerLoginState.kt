package com.example.dpop.orchestrator.journey.state

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

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
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@t")
@JsonSubTypes(
    JsonSubTypes.Type(value = ConfirmPeerLoginState.Requested::class, name = "Requested"),
    JsonSubTypes.Type(value = ConfirmPeerLoginState.ConfirmationRequired::class, name = "ConfirmationRequired"),
    JsonSubTypes.Type(value = ConfirmPeerLoginState.Confirming::class, name = "Confirming"),
    JsonSubTypes.Type(value = ConfirmPeerLoginState.OfferLogout::class, name = "OfferLogout")
)
sealed interface ConfirmPeerLoginState : JourneyState {

    /**
     * The wish, before the loa2 gate has been evaluated - both the entry-intent's initial state
     * AND the state the journey is parked in while a STEP_UP sub-journey runs (same reasoning as
     * [ManageAuthMethodsState.AddRequested]). On a cold entry, `ctx.account == null` means no
     * `DeviceAccountLink` exists yet - the strategy aborts right here rather than ever falling into
     * identification/registration.
     */
    data class Requested(val startedAuthenticated: Boolean) : ConfirmPeerLoginState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<String>): Set<String> = emptySet()
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
        override val offered: List<String>,
        override val declined: Set<String> = emptySet(),
        override val active: ToolRef? = null
    ) : ConfirmPeerLoginState, OfferingState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "auth"
        override val selectionTitle: String get() = "Web-Login bestätigen – Identität erneut bestätigen"
        override val selectionDescription: String?
            get() = "Bevor Sie den Web-Login bestätigen, weisen Sie sich noch einmal aus."
    }

    /**
     * loa2 satisfied - `confirm-qr-login` is the one and only candidate. Modeled as an
     * [OfferingState] with a single-element [offered] purely to reuse the existing
     * skip-the-selection-screen machinery ([JourneyService.nextFor]), not because there is
     * actually a choice.
     */
    data class Confirming(
        val startedAuthenticated: Boolean,
        override val active: ToolRef? = null,
        override val declined: Set<String> = emptySet()
    ) : ConfirmPeerLoginState, OfferingState {
        override val offered: List<String> get() = listOf("confirm-qr-login")
        override fun withActive(active: ToolRef?) = copy(active = active)
        override val selectionContext: String get() = "auth"
        override val selectionTitle: String get() = "Web-Login bestätigen"
        override val selectionDescription: String
            get() = "Ein Browser möchte sich mit Ihrem Konto anmelden. Bestätigen Sie das nur, wenn Sie diesen Login selbst ausgelöst haben."
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
        override fun activatable(availableTools: Set<String>): Set<String> = emptySet()
        override val active: ToolRef? get() = null
        override val prompt: Prompt
            get() = Prompt.Confirm(
                title = "Jetzt abmelden?",
                description = "Dieses Gerät war vor der Bestätigung nicht angemeldet - nur für diese eine " +
                    "Bestätigung wurde es kurz angemeldet. Jetzt wieder abmelden, oder angemeldet bleiben?",
                confirmLabel = "Abmelden",
                cancelLabel = "Angemeldet bleiben",
                destructive = false
            )
    }
}
