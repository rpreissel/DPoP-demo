package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.IntentStrategy
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.ReIdentifyState
import com.example.dpop.orchestrator.journey.state.StepUpState
import com.example.dpop.orchestrator.session.ChannelState
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
    override fun initialState(ctx: JourneyContext): StepUpState = StepUpState.Start(ctx.acrFloor, startingAcr = "none")

    override fun transition(state: StepUpState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            // No tool is ever activatable here (see AuthChoice), so this never actually sees a
            // Completed event - only the sub-journey/give-up-vs-offer events below.
            is StepUpState.Start -> when (event) {
                // Re-check whether the fresh proof already closes the gap before offering again.
                is JourneyEvent.SubJourneyFinished -> finishOrContinue(state.targetAcr, state.startingAcr, state.allowReIdentification, ctx)
                // No new evidence - re-deriving would just re-request the same RE_IDENTIFY again.
                is JourneyEvent.SubJourneyCancelled -> Transition.Cancel
                else -> offerAuth(state.targetAcr, state.startingAcr, state.allowReIdentification, ctx)
            }

            is StepUpState.AuthChoice -> when (event) {
                is JourneyEvent.Completed -> Transition.Perform(proofAction(event), resumeState = state)
                is JourneyEvent.Abandoned -> {
                    val declined = state.declined + event.tool.toolId
                    if ((state.offered.toSet() - declined).isEmpty()) {
                        offerReIdentOrGiveUp(state.targetAcr, state.startingAcr, state.allowReIdentification, ctx, whenNone = Transition.Cancel)
                    } else {
                        Transition.To(state.copy(declined = declined, active = null))
                    }
                }
                // ActionCompleted: re-check with the fresh, post-proof context.
                else -> finishOrContinue(state.targetAcr, state.startingAcr, state.allowReIdentification, ctx)
            }
        }

    override fun cancelledTo(state: StepUpState): ChannelState = ChannelState.AUTHENTICATED

    private fun proofAction(event: JourneyEvent.Completed): Action = when (val outcome = event.outcome) {
        is ToolOutcome.Completed.Authenticated -> Action.AcceptProof(event.tool, outcome, useOutcomeAccount = false, bindDevice = true)
        is ToolOutcome.Completed.Identified, is ToolOutcome.Completed.Enrolled, is ToolOutcome.Completed.Approved ->
            error("${event.tool.toolId} is not offered by STEP_UP")
    }

    private fun finishOrContinue(targetAcr: String, startingAcr: String, allowReIdentification: Boolean, ctx: JourneyContext): Transition {
        val account = ctx.requireAccount()
        if (ctx.policy.isSatisfied(ctx.evidence, targetAcr, account)) return Transition.Authenticated
        return offerAuth(targetAcr, startingAcr, allowReIdentification, ctx)
    }

    private fun offerAuth(targetAcr: String, startingAcr: String, allowReIdentification: Boolean, ctx: JourneyContext): Transition {
        val account = ctx.requireAccount()
        val candidates = CandidateTools.forAuth(account, targetAcr, ctx)
        if (candidates.isNotEmpty()) {
            return Transition.To(StepUpState.AuthChoice(targetAcr, startingAcr, candidates, allowReIdentification))
        }
        return offerReIdentOrGiveUp(
            targetAcr, startingAcr, allowReIdentification, ctx,
            whenNone = Transition.Abort("Gefordertes Sicherheitsniveau ist mit den vorhandenen Methoden nicht erreichbar. ${ctx.policy.unreachableReason(account, targetAcr)}")
        )
    }

    /**
     * Asks first (see class doc) if re-identification could close the gap; [whenNone] otherwise.
     * Never even looks at [CandidateTools.forReIdentification] when [allowReIdentification] is
     * false (see [StepUpState.Start.allowReIdentification]'s own doc) - straight to [whenNone].
     */
    private fun offerReIdentOrGiveUp(targetAcr: String, startingAcr: String, allowReIdentification: Boolean, ctx: JourneyContext, whenNone: Transition): Transition =
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
