package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.IntentStrategy
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.ConfirmPeerLoginState
import com.example.dpop.orchestrator.session.AcrLevels
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Component

/**
 * Approve or decline a WEB-channel `auth-qr`/`auth-qr-lookup` pairing (docs/ideen/qr-login-ueber-
 * app.md #4). Reachable both as an entry intent (cold app, `POST /app/channels`) and on an
 * already-authenticated channel ([AuthIntent.CONFIRM_PEER_LOGIN]'s own doc) - both land on
 * [ConfirmPeerLoginState.Requested] and share this one gate.
 *
 * Same anti-self-escalation reasoning as [ManageAuthMethodsStrategy]: the CURRENT session must
 * first prove loa2 before it may vouch for a login elsewhere, so a hijacked loa1 session can't
 * approve on its own say-so. The wish survives the detour the same way - parked in
 * [ConfirmPeerLoginState.Requested] while a STEP_UP sub-journey runs, re-evaluated afterwards.
 * STEP_UP itself only ever offers device-bound methods for an already-known account, never
 * identification - which is exactly why this strategy needs exactly one guard of its own (no
 * account at all) rather than a whole restricted fallback chain.
 *
 * Same [DeleteAccountStrategy]-shaped re-proof requirement, and for the same reason: a channel
 * that already independently satisfied loa2 (evidence of unknown age, possibly from a hijacked
 * session) must still prove ONE fresh factor - any active one, regardless of its own level -
 * right before offering `confirm-qr-login`, via [ConfirmPeerLoginState.ConfirmationRequired]. Only
 * when the loa2 evidence was just freshly produced by THIS journey's own gate-triggered STEP_UP
 * does that count as the fresh proof already, skipping straight to [ConfirmPeerLoginState.Confirming]
 * - a redundant second re-proof right after a step-up would gain nothing. That re-proof's outcome
 * is deliberately never recorded as `MethodEvidence` either (same reasoning as
 * [DeleteAccountStrategy]): it authorizes exactly this one confirmation, never a durable claim.
 */
@Component
class ConfirmPeerLoginStrategy : IntentStrategy<ConfirmPeerLoginState> {

    override val intent = AuthIntent.CONFIRM_PEER_LOGIN

    override fun initialState(ctx: JourneyContext): ConfirmPeerLoginState =
        ConfirmPeerLoginState.Requested(startedAuthenticated = false)

    override fun transition(state: ConfirmPeerLoginState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is ConfirmPeerLoginState.Requested -> when (event) {
                // Same reasoning as ManageAuthMethodsStrategy.AddRequested: a genuine
                // SubJourneyCancelled (the step-up was declined) ends this wish outright instead of
                // re-requesting the identical step-up forever.
                is JourneyEvent.SubJourneyCancelled -> Transition.Cancel
                // Returning from the gate's OWN step-up - that fresh loa2 proof already counts as
                // the confirmation this intent requires, straight to Confirming, no redundant
                // second re-proof (see class doc). Checked explicitly rather than assumed (same
                // reasoning as DeleteAccountStrategy's own SubJourneyFinished branch): a future
                // second sub-journey type, or a step-up that fell short of loa2, must not be
                // silently mistaken for sufficient proof - falling back to the ordinary
                // gate()/offerReconfirmation() path re-evaluates from scratch instead.
                is JourneyEvent.SubJourneyFinished ->
                    if (event.intent == AuthIntent.STEP_UP && AcrLevels.rank(event.achievedAcr) >= AcrLevels.rank(REQUIRED_ACR)) {
                        Transition.To(ConfirmPeerLoginState.Confirming(state.startedAuthenticated))
                    } else {
                        gate(ctx, state.startedAuthenticated) ?: offerReconfirmation(ctx, state.startedAuthenticated)
                    }
                // First evaluation (cold entry or an already-authenticated channel calling in) -
                // gate() is null only when loa2 was ALREADY satisfied independently of this run,
                // which is exactly the case that still needs one fresh re-proof.
                else -> gate(ctx, state.startedAuthenticated) ?: offerReconfirmation(ctx, state.startedAuthenticated)
            }

            is ConfirmPeerLoginState.ConfirmationRequired -> when (event) {
                is JourneyEvent.Abandoned -> {
                    val declined = state.declined + event.tool.toolId
                    if ((state.offered.toSet() - declined).isEmpty()) Transition.Cancel
                    else Transition.To(state.copy(declined = declined, active = null))
                }
                // Any active factor, at any level, is sufficient (CandidateTools.forReconfirmation)
                // - the re-proof itself is never recorded as MethodEvidence (class doc), it just
                // clears the way to Confirming.
                is JourneyEvent.Completed -> when (event.outcome) {
                    is ToolOutcome.Completed.Authenticated -> Transition.To(ConfirmPeerLoginState.Confirming(state.startedAuthenticated))
                    is ToolOutcome.Completed.Identified, is ToolOutcome.Completed.Enrolled, is ToolOutcome.Completed.Approved ->
                        error("${event.tool.toolId} is not offered by CONFIRM_PEER_LOGIN's ConfirmationRequired")
                }
                else -> error("ConfirmationRequired does not understand $event")
            }

            is ConfirmPeerLoginState.Confirming -> when (event) {
                // Backing out of confirm-qr-login is not declining the wish - the only candidate
                // comes back, exactly like ManageAuthMethodsStrategy.Enrolling.
                is JourneyEvent.Abandoned -> Transition.To(state.copy(active = null))
                is JourneyEvent.Completed -> Transition.Perform(proofAction(event), resumeState = state)
                // The approval just ran. A channel that was already authenticated before this
                // journey started stays that way; one that authenticated ONLY for this single
                // confirmation gets asked whether to log back out (OfferLogout) instead of silently
                // staying signed in.
                is JourneyEvent.ActionCompleted ->
                    if (state.startedAuthenticated) Transition.Authenticated else Transition.To(ConfirmPeerLoginState.OfferLogout)
                else -> error("Confirming does not understand $event")
            }

            is ConfirmPeerLoginState.OfferLogout -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    ACCEPT -> Transition.Logout
                    DECLINE -> Transition.Authenticated
                    else -> error("OfferLogout does not understand answer '${event.answer}'")
                }
                else -> error("OfferLogout only accepts JourneyEvent.Answered")
            }
        }

    override fun cancelledTo(state: ConfirmPeerLoginState): ChannelState = ChannelState.AUTHENTICATED

    private fun proofAction(event: JourneyEvent.Completed): Action = when (val outcome = event.outcome) {
        is ToolOutcome.Completed.Approved -> Action.RecordApproval(event.tool, outcome)
        is ToolOutcome.Completed.Identified, is ToolOutcome.Completed.Enrolled, is ToolOutcome.Completed.Authenticated ->
            error("${event.tool.toolId} is not offered by CONFIRM_PEER_LOGIN")
    }

    /**
     * Null once the session already carries loa2 and the caller may proceed. `null` for the
     * account itself (a cold entry with no `DeviceAccountLink`) is the one case
     * [IntentStrategy.transition] must never let reach [JourneyContext.requireAccount] - it aborts
     * here instead of crashing there, since no identification/registration fallback is ever
     * offered by this intent (see its own KDoc).
     */
    private fun gate(ctx: JourneyContext, startedAuthenticated: Boolean): Transition? {
        val account = ctx.account
            ?: return Transition.Abort("Dieses Gerät ist noch keinem Konto zugeordnet - bitte zuerst regulär anmelden.")
        if (ctx.policy.isSatisfied(ctx.evidence, REQUIRED_ACR, account)) return null
        return Transition.RequireSubJourney(
            AuthIntent.STEP_UP, REQUIRED_ACR,
            resumeWith = ConfirmPeerLoginState.Requested(startedAuthenticated)
        )
    }

    /** 1:1 with [DeleteAccountStrategy]'s own `offerReconfirmation` - same candidate set, same "no active factor left" fallback. */
    private fun offerReconfirmation(ctx: JourneyContext, startedAuthenticated: Boolean): Transition {
        val candidates = CandidateTools.forReconfirmation(ctx.requireAccount(), ctx)
        return if (candidates.isEmpty()) {
            Transition.Abort("Kein aktiver Faktor zur erneuten Bestaetigung verfuegbar")
        } else {
            Transition.To(ConfirmPeerLoginState.ConfirmationRequired(startedAuthenticated, candidates))
        }
    }

    companion object {
        const val REQUIRED_ACR = "loa2"

        /** The two answers [ConfirmPeerLoginState.OfferLogout] understands (see JourneyEvent.Answered). */
        const val ACCEPT = "accept"
        const val DECLINE = "decline"
    }
}
