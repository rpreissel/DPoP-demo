package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.IntentStrategy
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.ManageAuthMethodsState
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Component

/**
 * Add or remove authentication methods on an already authenticated channel
 * (docs/04-orchestrierung.md #3).
 *
 * The only intent without a policy goal: ONE successful enrollment ends it, regardless of the
 * level reached - the channel was already AUTHENTICATED before it started. Adding a second method
 * means starting another journey.
 *
 * Both operations first require the CURRENT session to prove loa2, following the same
 * anti-self-escalation reasoning as the enrolledUnderAcr cap: a hijacked loa1 session must not be
 * able to add or remove credentials on its own say-so. The wish itself survives that detour: the
 * journey stays parked in [ManageAuthMethodsState.AddRequested]/[ManageAuthMethodsState.RemoveRequested] while the
 * step-up sub-journey runs, and re-evaluating that same state afterwards both re-checks the gate
 * and carries out what was originally asked for.
 */
@Component
class ManageAuthMethodsStrategy : IntentStrategy<ManageAuthMethodsState> {

    override val intent = AuthIntent.MANAGE_AUTH_METHODS

    override fun initialState(ctx: JourneyContext): ManageAuthMethodsState = ManageAuthMethodsState.AddRequested

    override fun transition(state: ManageAuthMethodsState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            // gate() re-requests the very same STEP_UP again if unsatisfied - correct on a fresh
            // request or after a genuine (if insufficient) SubJourneyFinished, but on
            // SubJourneyCancelled it would just re-show the identical step-up prompt forever
            // instead of respecting the decline, so that case gives up on its own (Transition.Cancel)
            // instead of calling gate() at all.
            is ManageAuthMethodsState.AddRequested ->
                if (event is JourneyEvent.SubJourneyCancelled) Transition.Cancel else gate(state, ctx) ?: offerEnrollment(ctx)

            is ManageAuthMethodsState.RemoveRequested -> when (event) {
                is JourneyEvent.SubJourneyCancelled -> Transition.Cancel
                // The removal just ran.
                is JourneyEvent.ActionCompleted -> Transition.Authenticated
                else -> gate(state, ctx) ?: Transition.Perform(Action.Remove(state.methodInstanceId), resumeState = state)
            }

            is ManageAuthMethodsState.Enrolling -> when (event) {
                // Backing out here means picking a different method, not abandoning the wish -
                // the full choice comes back. Giving up entirely is DELETE .../journey.
                is JourneyEvent.Abandoned -> Transition.To(state.copy(active = null))
                is JourneyEvent.Completed -> Transition.Perform(proofAction(event), resumeState = state)
                // The enrollment just ran - one successful enrollment ends this intent.
                else -> Transition.Authenticated
            }
        }

    override fun cancelledTo(state: ManageAuthMethodsState): ChannelState = ChannelState.AUTHENTICATED

    /**
     * Voluntary enrollment on a channel that is already authenticated - the device is already
     * known, so binding it again is a harmless no-op that keeps a newly enrolled device
     * credential reachable next time.
     */
    private fun proofAction(event: JourneyEvent.Completed): Action = when (val outcome = event.outcome) {
        is ToolOutcome.Completed.Enrolled -> Action.AdoptCredential(event.tool, outcome, bindDevice = true)
        is ToolOutcome.Completed.Identified, is ToolOutcome.Completed.Authenticated, is ToolOutcome.Completed.Approved ->
            error("${event.tool.toolId} is not offered by MANAGE")
    }

    /** Null once the session already carries loa2 and the caller may proceed. */
    private fun gate(requested: ManageAuthMethodsState, ctx: JourneyContext): Transition? {
        val account = ctx.requireAccount()
        if (ctx.policy.isSatisfied(ctx.evidence, REQUIRED_ACR, account)) return null
        return Transition.RequireSubJourney(AuthIntent.STEP_UP, REQUIRED_ACR, resumeWith = requested)
    }

    private fun offerEnrollment(ctx: JourneyContext): Transition {
        val candidates = CandidateTools.forEnrollment(ctx.requireAccount(), ctx.acrFloor, ctx)
        return if (candidates.isEmpty()) {
            // Not an error, just nothing left to add (docs/07-betrieb.md #1: HTTP errors are for
            // disrupted flows, not an expectable "you already have everything").
            Transition.Authenticated
        } else {
            Transition.To(ManageAuthMethodsState.Enrolling(candidates))
        }
    }

    companion object {
        const val REQUIRED_ACR = "loa2"
    }
}
