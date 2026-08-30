package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.Decision
import com.example.dpop.orchestrator.journey.IntentStrategy
import com.example.dpop.orchestrator.journey.Interpretation
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.state.ReIdentifyState
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Component

/**
 * "No active method reaches the target - re-identify instead?" Shared by FAST_ACCESS/
 * LOOKUP_LOGIN/STEP_UP (docs/04-orchestrierung.md #3, #6) - never an entry intent, only ever
 * reached via [Decision.RequireSubJourney] once no active method can close the caller's own gap.
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

    override fun interpret(state: ReIdentifyState, tool: ToolDescriptor, outcome: ToolOutcome.Completed): Interpretation =
        when (outcome) {
            is ToolOutcome.Completed.Identified -> Interpretation.ConfirmIdentity
            is ToolOutcome.Completed.Authenticated,
            is ToolOutcome.Completed.Enrolled ->
                error("${tool.toolId} is not offered by RE_IDENTIFY")
        }

    override fun next(state: ReIdentifyState, event: JourneyEvent, ctx: JourneyContext): Decision =
        when (state) {
            is ReIdentifyState.OfferReIdent -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    "accept" -> offerIdentifying(state.targetAcr, state.startingAcr, ctx) ?: Decision.Cancel
                    "decline" -> Decision.Cancel
                    else -> error("OfferReIdent does not understand answer '${event.answer}'")
                }
                // Started: always present the prompt, unconditionally.
                else -> Decision.Advance(state)
            }

            is ReIdentifyState.Identifying -> when (event) {
                is JourneyEvent.Abandoned -> {
                    val declined = state.declined + event.tool.toolId
                    if ((state.offered.toSet() - declined).isEmpty()) Decision.Cancel
                    else Decision.Advance(state.copy(declined = declined, active = null))
                }
                // Identity confirmed - this identification's own maxAcr already IS the achieved level.
                else -> Decision.Finish
            }
        }

    /** [ReIdentifyState.startingAcr] is the only signal available here (docs/04-orchestrierung.md): "none" means the caller (FAST_ACCESS/LOOKUP_LOGIN) had no session yet, a real level means the caller (STEP_UP) was already AUTHENTICATED - declining must not de-authenticate that session. */
    override fun onCancel(state: ReIdentifyState): ChannelState =
        if (state.startingAcr == "none") ChannelState.ANONYMOUS else ChannelState.AUTHENTICATED

    private fun offerIdentifying(targetAcr: String, startingAcr: String, ctx: JourneyContext): Decision? {
        val candidates = CandidateTools.forReIdentification(targetAcr, ctx)
        return candidates.takeIf { it.isNotEmpty() }
            ?.let { Decision.Advance(ReIdentifyState.Identifying(targetAcr, startingAcr, it)) }
    }
}
