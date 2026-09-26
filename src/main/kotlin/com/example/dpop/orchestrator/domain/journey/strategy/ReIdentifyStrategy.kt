package com.example.dpop.orchestrator.domain.journey.strategy

import com.example.dpop.orchestrator.domain.journey.Action
import com.example.dpop.orchestrator.domain.journey.declineTool
import com.example.dpop.orchestrator.domain.AuthIntent
import com.example.dpop.orchestrator.domain.journey.CandidateTools
import com.example.dpop.orchestrator.domain.journey.IntentStrategy
import com.example.dpop.orchestrator.domain.journey.JourneyContext
import com.example.dpop.orchestrator.domain.journey.JourneyEvent
import com.example.dpop.orchestrator.domain.journey.Transition
import com.example.dpop.orchestrator.domain.journey.state.Offer
import com.example.dpop.orchestrator.domain.journey.state.ReIdentifyState
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.ToolOutcome

/**
 * "No active method reaches the target - re-identify instead?" Shared by FAST_ACCESS/
 * LOOKUP_LOGIN/STEP_UP (docs/04-orchestrierung.md #3, #6) - never an entry intent, only ever
 * reached via [Transition.RequireSubJourney] once no active method can close the caller's own gap.
 * One implementation instead of three near-identical ones. What a fresh identification is allowed
 * to mean is NOT decided here though: [Action.RecordIdentification]'s single handler re-reads the account
 * this journey already holds and gates any move to a different one through its own `accountOf`
 * rule - so a session that merely proved a lower level cannot smuggle in someone else's identity,
 * no matter which strategy asked for the sub-journey.
 */
class ReIdentifyStrategy : IntentStrategy<ReIdentifyState> {

    override val intent = AuthIntent.RE_IDENTIFY

    /** Never entered without a target; only reachable as a sub-journey, which seeds the real one. */
    override fun initialState(ctx: JourneyContext): ReIdentifyState = ReIdentifyState.OfferReIdent(ctx.acrFloor, startingAcr = AcrLevel.NONE)

    override fun transition(state: ReIdentifyState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is ReIdentifyState.OfferReIdent -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    "accept" -> offerIdentifying(state.targetAcr, state.startingAcr, state.wording, ctx) ?: Transition.Cancel
                    "decline" -> Transition.Cancel
                    else -> error("OfferReIdent does not understand answer '${event.answer}'")
                }
                // Started: always present the prompt, unconditionally.
                else -> Transition.To(state)
            }

            is ReIdentifyState.Identifying -> when (event) {
                is JourneyEvent.Abandoned -> declineTool(state, event.tool.toolId, ctx) { Transition.Cancel }
                is JourneyEvent.Completed -> Transition.Perform(proofAction(event), resumeState = state)
                // Identity confirmed - this identification's own maxAcr already IS the achieved level.
                else -> Transition.Authenticated
            }
        }

    private fun proofAction(event: JourneyEvent.Completed): Action = when (val outcome = event.outcome) {
        is ToolOutcome.Completed.Identified -> Action.RecordIdentification(event.tool, outcome)
        is ToolOutcome.Completed.Authenticated, is ToolOutcome.Completed.Enrolled, is ToolOutcome.Completed.Approved, is ToolOutcome.Completed.Attested ->
            error("${event.tool.toolId} is not offered by RE_IDENTIFY")
    }

    private fun offerIdentifying(targetAcr: AcrLevel, startingAcr: AcrLevel, wording: ReIdentifyState.Wording?, ctx: JourneyContext): Transition? {
        val candidates = CandidateTools.forReIdentification(targetAcr, ctx)
        return candidates.takeIf { it.isNotEmpty() }
            ?.let { Transition.To(ReIdentifyState.Identifying(targetAcr, startingAcr, Offer(it), wording = wording)) }
    }
}
