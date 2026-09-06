package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.IntentStrategy
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.ReIdentifyState
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Component

/**
 * "No active method reaches the target - re-identify instead?" Shared by FAST_ACCESS/
 * LOOKUP_LOGIN/STEP_UP (docs/04-orchestrierung.md #3, #6) - never an entry intent, only ever
 * reached via [Transition.RequireSubJourney] once no active method can close the caller's own gap.
 * One implementation instead of three near-identical ones means exactly one place decides what a
 * fresh identification is allowed to mean here: it always CONFIRMS the account the caller already
 * resolved (`ConfirmIdentity`), never adopts a different one - a session that merely proved a
 * lower level must not be able to smuggle in someone else's identity.
 */
@Component
class ReIdentifyStrategy : IntentStrategy<ReIdentifyState> {

    override val intent = AuthIntent.RE_IDENTIFY

    /** Never entered without a target; only reachable as a sub-journey, which seeds the real one. */
    override fun initialState(ctx: JourneyContext): ReIdentifyState = ReIdentifyState.OfferReIdent(ctx.acrFloor, startingAcr = "none")

    override fun initialStateForSubJourneyAcr(targetAcr: String, startingAcr: String): ReIdentifyState =
        ReIdentifyState.OfferReIdent(targetAcr, startingAcr)

    override fun transition(state: ReIdentifyState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is ReIdentifyState.OfferReIdent -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    "accept" -> offerIdentifying(state.targetAcr, state.startingAcr, ctx) ?: Transition.Cancel
                    "decline" -> Transition.Cancel
                    else -> error("OfferReIdent does not understand answer '${event.answer}'")
                }
                // Started: always present the prompt, unconditionally.
                else -> Transition.To(state)
            }

            is ReIdentifyState.Identifying -> when (event) {
                is JourneyEvent.Abandoned -> {
                    val declined = state.declined + event.tool.toolId
                    if ((state.offered.toSet() - declined).isEmpty()) Transition.Cancel
                    else Transition.To(state.copy(declined = declined, active = null))
                }
                is JourneyEvent.Completed -> Transition.Perform(proofAction(event), resumeState = state)
                // Identity confirmed - this identification's own maxAcr already IS the achieved level.
                else -> Transition.Authenticated
            }
        }

    private fun proofAction(event: JourneyEvent.Completed): Action = when (val outcome = event.outcome) {
        is ToolOutcome.Completed.Identified -> Action.ConfirmIdentity(event.tool, outcome)
        is ToolOutcome.Completed.Authenticated, is ToolOutcome.Completed.Enrolled ->
            error("${event.tool.toolId} is not offered by RE_IDENTIFY")
    }

    /** [ReIdentifyState.startingAcr] is the only signal available here (docs/04-orchestrierung.md): "none" means the caller (FAST_ACCESS/LOOKUP_LOGIN) had no session yet, a real level means the caller (STEP_UP) was already AUTHENTICATED - declining must not de-authenticate that session. */
    override fun cancelledTo(state: ReIdentifyState): ChannelState =
        if (state.startingAcr == "none") ChannelState.ANONYMOUS else ChannelState.AUTHENTICATED

    private fun offerIdentifying(targetAcr: String, startingAcr: String, ctx: JourneyContext): Transition? {
        val candidates = CandidateTools.forReIdentification(targetAcr, ctx)
        return candidates.takeIf { it.isNotEmpty() }
            ?.let { Transition.To(ReIdentifyState.Identifying(targetAcr, startingAcr, it)) }
    }
}
