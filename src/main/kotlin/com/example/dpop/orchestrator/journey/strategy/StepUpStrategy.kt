package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.Decision
import com.example.dpop.orchestrator.journey.IntentStrategy
import com.example.dpop.orchestrator.journey.Interpretation
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.state.StepUpState
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Component

/**
 * Raise the level of an already authenticated session (docs/04-orchestrierung.md #3).
 *
 * Whenever no active method can close the gap - whether this runs standalone or as another
 * journey's precondition ([JourneyContext.isSubJourney]) - a [Decision.RequireSubJourney] into
 * `RE_IDENTIFY` ([ReIdentifyStrategy]) asks first before ever falling through to a fresh
 * identification: re-identification is a heavier action than picking another factor, so it's
 * never a silent shortcut, and this way the confirmation/interpretation logic lives in exactly
 * one shared place instead of being duplicated per intent.
 */
@Component
class StepUpStrategy : IntentStrategy<StepUpState> {

    override val intent = AuthIntent.STEP_UP

    /** Never entered without a target; only reachable as a sub-journey, which seeds the real one. */
    override fun initialState(ctx: JourneyContext): StepUpState = StepUpState.Start(ctx.acrFloor, startingAcr = "none")

    override fun initialStateForSubJourneyAcr(targetAcr: String, startingAcr: String): StepUpState =
        StepUpState.Start(targetAcr, startingAcr)

    override fun interpret(state: StepUpState, tool: ToolDescriptor, outcome: ToolOutcome.Completed): Interpretation =
        when (outcome) {
            is ToolOutcome.Completed.Authenticated ->
                Interpretation.AcceptProof(useOutcomeAccount = false, bindDevice = true)
            is ToolOutcome.Completed.Identified,
            is ToolOutcome.Completed.Enrolled -> error("${tool.toolId} is not offered by STEP_UP")
        }

    override fun next(state: StepUpState, event: JourneyEvent, ctx: JourneyContext): Decision =
        when (state) {
            is StepUpState.Start -> when (event) {
                // Resumed after a RE_IDENTIFY sub-journey - re-check whether the fresh proof
                // already closes the gap before trying to offer auth methods again.
                is JourneyEvent.SubJourneyFinished -> finishOrContinue(state.targetAcr, state.startingAcr, ctx)
                // RE_IDENTIFY was declined instead - no new evidence, and re-deriving via
                // offerAuth/offerReIdentOrGiveUp would just re-request the very same RE_IDENTIFY
                // again (candidates unchanged), the identical confirm prompt forever - gives up on
                // its own instead.
                is JourneyEvent.SubJourneyCancelled -> Decision.Cancel
                else -> offerAuth(state.targetAcr, state.startingAcr, ctx)
            }

            is StepUpState.AuthChoice -> when (event) {
                is JourneyEvent.Abandoned -> {
                    val declined = state.declined + event.tool.toolId
                    if ((state.offered.toSet() - declined).isEmpty()) {
                        offerReIdentOrGiveUp(state.targetAcr, state.startingAcr, ctx, whenNone = Decision.Cancel)
                    } else {
                        Decision.Advance(state.copy(declined = declined, active = null))
                    }
                }
                else -> finishOrContinue(state.targetAcr, state.startingAcr, ctx)
            }
        }

    override fun onCancel(state: StepUpState): ChannelState = ChannelState.AUTHENTICATED

    private fun finishOrContinue(targetAcr: String, startingAcr: String, ctx: JourneyContext): Decision {
        val account = ctx.requireAccount()
        if (ctx.policy.isSatisfied(ctx.evidence, targetAcr, account)) return Decision.Finish
        return offerAuth(targetAcr, startingAcr, ctx)
    }

    private fun offerAuth(targetAcr: String, startingAcr: String, ctx: JourneyContext): Decision {
        val account = ctx.requireAccount()
        val candidates = CandidateTools.forAuth(account, targetAcr, ctx)
        if (candidates.isNotEmpty()) {
            return Decision.Advance(StepUpState.AuthChoice(targetAcr, startingAcr, candidates))
        }
        return offerReIdentOrGiveUp(
            targetAcr, startingAcr, ctx,
            whenNone = Decision.Abort("Gefordertes Sicherheitsniveau ist mit den vorhandenen Methoden nicht erreichbar. ${ctx.policy.unreachableReason(account, targetAcr)}")
        )
    }

    /** Asks first (see class doc) if re-identification could close the gap; [whenNone] otherwise. */
    private fun offerReIdentOrGiveUp(targetAcr: String, startingAcr: String, ctx: JourneyContext, whenNone: Decision): Decision =
        if (CandidateTools.forReIdentification(targetAcr, ctx).isNotEmpty()) {
            Decision.RequireSubJourney(AuthIntent.RE_IDENTIFY, targetAcr, resumeWith = StepUpState.Start(targetAcr, startingAcr))
        } else {
            whenNone
        }
}
