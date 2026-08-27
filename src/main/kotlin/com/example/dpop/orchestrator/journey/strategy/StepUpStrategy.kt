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
 * [StepUpState.ReIdentifying] is offered only while this journey runs as another one's
 * precondition ([JourneyContext.isSubJourney]). That is the whole distinction the old
 * `allowReIdent` boolean encoded: an ordinary step-up keeps offering enrolled methods only, while
 * a step-up demanded as a gate must have SOME way out even for an account with a single method.
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
            // The account is already known here. A re-identification must only ever CONFIRM it,
            // never switch to a different one - otherwise a session that merely proved loa1 could
            // smuggle in someone else's identity and take over another account outright.
            is ToolOutcome.Completed.Identified -> Interpretation.ConfirmIdentity
            is ToolOutcome.Completed.Authenticated ->
                Interpretation.AcceptProof(useOutcomeAccount = false, bindDevice = true)
            is ToolOutcome.Completed.Enrolled -> error("${tool.toolId} is not offered by STEP_UP")
        }

    override fun next(state: StepUpState, event: JourneyEvent, ctx: JourneyContext): Decision =
        when (state) {
            is StepUpState.Start -> offerAuth(state.targetAcr, state.startingAcr, ctx)

            is StepUpState.AuthChoice -> when (event) {
                is JourneyEvent.Abandoned -> {
                    val declined = state.declined + event.tool.toolId
                    if ((state.offered.toSet() - declined).isEmpty()) {
                        offerReIdent(state.targetAcr, state.startingAcr, ctx) ?: Decision.Cancel
                    } else {
                        Decision.Advance(state.copy(declined = declined, active = null))
                    }
                }
                else -> finishOrContinue(state.targetAcr, state.startingAcr, ctx)
            }

            is StepUpState.ReIdentifying -> when (event) {
                is JourneyEvent.Abandoned -> Decision.Cancel
                // A re-identification prices in its own full trust level, so once it clears the
                // target there is nothing left to prove.
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
        return offerReIdent(targetAcr, startingAcr, ctx)
            ?: Decision.Abort("Gefordertes Sicherheitsniveau ist mit den vorhandenen Methoden nicht erreichbar")
    }

    /**
     * The way out of the one-method dead end - see [StepUpState.ReIdentifying]. Null when this is
     * an ordinary step-up, where re-identification must not appear as a generic shortcut.
     */
    private fun offerReIdent(targetAcr: String, startingAcr: String, ctx: JourneyContext): Decision? {
        if (!ctx.isSubJourney) return null
        val candidates = CandidateTools.forReIdentification(targetAcr, ctx)
        return candidates.takeIf { it.isNotEmpty() }
            ?.let { Decision.Advance(StepUpState.ReIdentifying(targetAcr, startingAcr, it)) }
    }
}
