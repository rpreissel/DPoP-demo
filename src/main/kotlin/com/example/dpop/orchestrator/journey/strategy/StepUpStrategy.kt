package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.declineTool
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.IntentStrategy
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.Offer
import com.example.dpop.orchestrator.journey.state.ReIdentifyState
import com.example.dpop.orchestrator.journey.toAuthAbortMessage
import com.example.dpop.orchestrator.journey.state.StepUpState
import com.example.dpop.orchestrator.policy.EvidenceAxis
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Component

/**
 * Raise the level of an already authenticated session (docs/04-orchestrierung.md #3).
 *
 * Whenever no active method can close the gap - whether this runs standalone or as another
 * journey's precondition ([JourneyContext.isSubJourney]) - a [Transition.RequireSubJourney] into
 * `RE_IDENTIFY` ([ReIdentifyStrategy]) asks first before ever falling through to a fresh
 * identification: re-identification is a heavier action than picking another factor, so it's
 * never a silent shortcut, and this way the confirmation/interpretation logic lives in exactly
 * one shared place instead of being duplicated per intent.
 */
@Component
class StepUpStrategy : IntentStrategy<StepUpState> {

    override val intent = AuthIntent.STEP_UP

    /** Never entered without a target; only reachable as a sub-journey, which seeds the real one. */
    override fun initialState(ctx: JourneyContext): StepUpState = StepUpState.Start(ctx.acrFloor, startingAcr = AcrLevel.NONE)

    override fun transition(state: StepUpState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            // No tool is ever activatable here (see AuthChoice), so this never actually sees a
            // Completed event - only the sub-journey/give-up-vs-offer events below.
            is StepUpState.Start -> when (event) {
                // Re-check whether the fresh proof already closes the gap before offering again.
                is JourneyEvent.SubJourneyFinished -> finishOrContinue(state.targetAcr, state.startingAcr, state.allowReIdentification, state.reason, ctx)
                // No new evidence - re-deriving would just re-request the same RE_IDENTIFY again.
                is JourneyEvent.SubJourneyCancelled -> Transition.Cancel
                else -> offerAuth(state.targetAcr, state.startingAcr, state.allowReIdentification, state.reason, ctx)
            }

            is StepUpState.AuthChoice -> when (event) {
                is JourneyEvent.Completed -> Transition.Perform(proofAction(event), resumeState = state)
                is JourneyEvent.Abandoned -> declineTool(state, event.tool.toolId, ctx) {
                    offerReIdentOrGiveUp(state.targetAcr, state.startingAcr, state.allowReIdentification, ctx, whenNone = Transition.Cancel)
                }
                // ActionCompleted: re-check with the fresh, post-proof context.
                else -> finishOrContinue(state.targetAcr, state.startingAcr, state.allowReIdentification, state.reason, ctx)
            }
        }

    override fun cancelledTo(state: StepUpState): ChannelState = ChannelState.AUTHENTICATED

    private fun proofAction(event: JourneyEvent.Completed): Action = when (val outcome = event.outcome) {
        is ToolOutcome.Completed.Authenticated -> Action.AcceptProof(event.tool, outcome)
        is ToolOutcome.Completed.Identified, is ToolOutcome.Completed.Enrolled, is ToolOutcome.Completed.Approved, is ToolOutcome.Completed.Attested ->
            error("${event.tool.toolId} is not offered by STEP_UP")
    }

    private fun finishOrContinue(targetAcr: AcrLevel, startingAcr: AcrLevel, allowReIdentification: Boolean, reason: String?, ctx: JourneyContext): Transition {
        val account = ctx.requireAccount()
        if (ctx.policy.isSatisfied(ctx.evidence, targetAcr, account)) return Transition.Authenticated
        return offerAuth(targetAcr, startingAcr, allowReIdentification, reason, ctx)
    }

    private fun offerAuth(targetAcr: AcrLevel, startingAcr: AcrLevel, allowReIdentification: Boolean, reason: String?, ctx: JourneyContext): Transition {
        val account = ctx.requireAccount()
        val candidates = CandidateTools.forAuth(account, targetAcr, ctx)
        if (candidates.isNotEmpty()) {
            // Any AUTHENTICATOR-axis evidence already on the channel (from an earlier pick in THIS
            // run, or from whatever originally got the channel this far) means this offer is asking
            // for an ADDITIONAL factor, not the first one - see AuthChoice.additionalFactorRound's
            // own doc for why that needs saying out loud.
            val additionalFactorRound = ctx.evidence.factors.any { it.axis == EvidenceAxis.AUTHENTICATOR }
            return Transition.To(StepUpState.AuthChoice(targetAcr, startingAcr, Offer(candidates), allowReIdentification, reason = reason, additionalFactorRound = additionalFactorRound))
        }
        return offerReIdentOrGiveUp(
            targetAcr, startingAcr, allowReIdentification, ctx,
            whenNone = Transition.Abort(ctx.policy.reachability(account, targetAcr).toAuthAbortMessage())
        )
    }

    /**
     * Asks first (see class doc) if re-identification could close the gap; [whenNone] otherwise.
     * Never even looks at [CandidateTools.forReIdentification] when [allowReIdentification] is
     * false (see [StepUpState.Start.allowReIdentification]'s own doc) - straight to [whenNone].
     */
    private fun offerReIdentOrGiveUp(targetAcr: AcrLevel, startingAcr: AcrLevel, allowReIdentification: Boolean, ctx: JourneyContext, whenNone: Transition): Transition =
        if (allowReIdentification && CandidateTools.forReIdentification(targetAcr, ctx).isNotEmpty()) {
            Transition.RequireSubJourney(
                AuthIntent.RE_IDENTIFY,
                // ctx.currentAcr, not the (possibly stale) startingAcr this STEP_UP run started
                // with - RE_IDENTIFY's own startingAcr only ever decides ANONYMOUS vs AUTHENTICATED
                // on cancel (ReIdentifyStrategy.cancelledTo), and a channel STEP_UP runs on is
                // always already-authenticated either way, so this is observational accuracy, not
                // a behavior change.
                seedWith = ReIdentifyState.forSubJourney(targetAcr, ctx.currentAcr),
                resumeWith = StepUpState.Start(targetAcr, startingAcr)
            )
        } else {
            whenNone
        }
}
