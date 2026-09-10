package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.IntentStrategy
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.LookupLoginState
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Component

/**
 * Log into an existing account without a paired device (docs/04-orchestrierung.md #3): the user
 * names an identifier and proves a credential.
 *
 * Two properties are structural here, not enforced by extra checks:
 * - There is no identification state at all: [LookupLoginState] has none that could offer one. A
 *   check that could be forgotten is replaced by a state that cannot be reached - falling back to
 *   a fresh identification once the account IS known runs as the shared `RE_IDENTIFY` sub-journey
 *   instead ([ReIdentifyStrategy]), never a state of this intent's own.
 * - The device link is never a side effect. It arises only from [LookupLoginState.OfferBinding], after
 *   the user agrees - this intent is chosen precisely by people who want no device binding.
 */
@Component
class LookupLoginStrategy : IntentStrategy<LookupLoginState> {

    override val intent = AuthIntent.LOOKUP_LOGIN

    override fun initialState(ctx: JourneyContext): LookupLoginState = LookupLoginState.Start

    override fun transition(state: LookupLoginState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is LookupLoginState.Start -> when (event) {
                // Re-check whether the fresh proof already closes the gap before offering again.
                is JourneyEvent.SubJourneyFinished -> settleOrRaise(ctx)
                // No new evidence - re-deriving would just re-request the same RE_IDENTIFY again.
                is JourneyEvent.SubJourneyCancelled -> Transition.Cancel
                else -> {
                    // The offered set IS "every tool that can resolve the account itself" - derived
                    // from the catalog, never listed. AuthPolicy.candidateTools cannot be used: it
                    // needs a resolved account, which by definition does not exist yet.
                    val tools = CandidateTools.forLookupLogin(ctx)
                    if (tools.isEmpty()) Transition.Abort("Kein Login-Verfahren ohne Geraetebindung verfuegbar")
                    else Transition.To(LookupLoginState.Credential(tools))
                }
            }

            // The only intent that trusts a tool to resolve the account itself - but only on the
            // FIRST proof, which is the one that has no account yet.
            is LookupLoginState.Credential -> when (event) {
                is JourneyEvent.Completed -> Transition.Perform(proofAction(event, useOutcomeAccount = true), resumeState = state)
                is JourneyEvent.Abandoned -> {
                    val declined = state.declined + event.tool.toolId
                    if ((state.offered.toSet() - declined).isEmpty()) Transition.Cancel
                    else Transition.To(state.copy(declined = declined, active = null))
                }
                else -> settleOrRaise(ctx)
            }

            // Any further factor runs against the account already bound by the first one, exactly
            // like every other intent, so it must not be able to name a different one.
            is LookupLoginState.AdditionalFactor -> when (event) {
                is JourneyEvent.Completed -> Transition.Perform(proofAction(event, useOutcomeAccount = false), resumeState = state)
                is JourneyEvent.Abandoned -> {
                    val declined = state.declined + event.tool.toolId
                    // Giving up here cannot mean "finish anyway": the floor is still unmet, and
                    // finishing would put the channel in AUTHENTICATED below its own level.
                    if ((state.offered.toSet() - declined).isEmpty()) Transition.Cancel
                    else Transition.To(state.copy(declined = declined, active = null))
                }
                else -> settleOrRaise(ctx)
            }

            // The client answers this via JourneyService.answer; the journey ends here either way,
            // but only ACCEPT asks the machine to actually link the device.
            is LookupLoginState.OfferBinding -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    ACCEPT -> Transition.Perform(Action.LinkDevice(state.accountId), resumeState = state)
                    DECLINE -> Transition.Authenticated
                    else -> error("OfferBinding does not understand answer '${event.answer}'")
                }
                is JourneyEvent.ActionCompleted -> Transition.Authenticated
                else -> error("OfferBinding only accepts JourneyEvent.Answered")
            }
        }

    override fun cancelledTo(state: LookupLoginState): ChannelState = ChannelState.ANONYMOUS

    /**
     * Neither [ToolOutcome.Completed.Identified] nor [ToolOutcome.Completed.Enrolled] can be
     * offered by any state of this intent; reaching here would mean the state machine let through
     * a tool it never offered.
     */
    private fun proofAction(event: JourneyEvent.Completed, useOutcomeAccount: Boolean): Action =
        when (val outcome = event.outcome) {
            is ToolOutcome.Completed.Authenticated -> Action.AcceptProof(event.tool, outcome, useOutcomeAccount, bindDevice = false)
            is ToolOutcome.Completed.Identified, is ToolOutcome.Completed.Enrolled, is ToolOutcome.Completed.Approved ->
                error("${event.tool.toolId} is not offered by LOGIN_LOOKUP")
        }

    /**
     * The channel's own acrFloor applies here like it does to every other intent - forgetting it
     * would let a channel opened with `requiredAcr: "loa3"` reach AUTHENTICATED on one loa1
     * factor, since `JourneyService.finish` sets AUTHENTICATED unconditionally.
     *
     * Unlike FAST_ACCESS/STEP_UP there is no ENROLLMENT fallback: this intent exists to log an
     * EXISTING account in from an unpaired device, so an account that cannot reach the floor with
     * what it already has must not grow new credentials on an unproven device. Re-identification
     * stays available: it adds no lasting credential, only re-confirms the same account at a
     * higher trust level (`RE_IDENTIFY`'s `ConfirmIdentity`).
     */
    private fun settleOrRaise(ctx: JourneyContext): Transition {
        val account = ctx.requireAccount()
        if (ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) {
            return Transition.To(LookupLoginState.OfferBinding(account.accountId))
        }
        val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
        if (candidates.isNotEmpty()) {
            return Transition.To(LookupLoginState.AdditionalFactor(candidates))
        }
        return if (CandidateTools.forReIdentification(ctx.acrFloor, ctx).isNotEmpty()) {
            Transition.RequireSubJourney(AuthIntent.RE_IDENTIFY, ctx.acrFloor, resumeWith = LookupLoginState.Start)
        } else {
            Transition.Abort("Gefordertes Sicherheitsniveau ist mit den vorhandenen Methoden nicht erreichbar. ${ctx.policy.unreachableReason(account, ctx.acrFloor)}")
        }
    }

    companion object {
        /** The two answers [LookupLoginState.OfferBinding] understands (see JourneyEvent.Answered). */
        const val ACCEPT = "accept"
        const val DECLINE = "decline"
    }
}
