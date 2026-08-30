package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.Decision
import com.example.dpop.orchestrator.journey.IntentStrategy
import com.example.dpop.orchestrator.journey.Interpretation
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.state.LookupLoginState
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.ToolDescriptor
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

    override fun interpret(state: LookupLoginState, tool: ToolDescriptor, outcome: ToolOutcome.Completed): Interpretation =
        when (outcome) {
            // The only intent that trusts a tool to resolve the account itself - but only on the
            // FIRST proof, which is the one that has no account yet. Any further factor runs
            // against the account already bound by that first one, exactly like every other
            // intent, so it must not be able to name a different one.
            is ToolOutcome.Completed.Authenticated -> Interpretation.AcceptProof(
                useOutcomeAccount = state is LookupLoginState.Credential,
                bindDevice = false
            )
            // Neither can be offered by any state of this intent; reaching here would mean the
            // state machine let through a tool it never offered.
            is ToolOutcome.Completed.Identified,
            is ToolOutcome.Completed.Enrolled ->
                error("${tool.toolId} is not offered by LOGIN_LOOKUP")
        }

    override fun next(state: LookupLoginState, event: JourneyEvent, ctx: JourneyContext): Decision =
        when (state) {
            is LookupLoginState.Start -> when (event) {
                // Resumed after a RE_IDENTIFY sub-journey - re-check whether the fresh proof
                // already closes the gap before offering credentials again.
                is JourneyEvent.SubJourneyFinished -> settleOrRaise(ctx)
                // RE_IDENTIFY was declined instead - no new evidence, and settleOrRaise would just
                // re-request the very same RE_IDENTIFY again (candidates unchanged), the identical
                // confirm prompt forever - gives up on its own instead.
                is JourneyEvent.SubJourneyCancelled -> Decision.Cancel
                else -> {
                    // The offered set IS "every tool that can resolve the account itself" - derived
                    // from the catalog, never listed. AuthPolicy.candidateTools cannot be used: it
                    // needs a resolved account, which by definition does not exist yet.
                    val tools = CandidateTools.forLookupLogin(ctx)
                    if (tools.isEmpty()) Decision.Abort("Kein Login-Verfahren ohne Geraetebindung verfuegbar")
                    else Decision.Advance(LookupLoginState.Credential(tools))
                }
            }

            is LookupLoginState.Credential -> when (event) {
                is JourneyEvent.Abandoned -> {
                    val declined = state.declined + event.tool.toolId
                    if ((state.offered.toSet() - declined).isEmpty()) Decision.Cancel
                    else Decision.Advance(state.copy(declined = declined, active = null))
                }
                else -> settleOrRaise(ctx)
            }

            is LookupLoginState.AdditionalFactor -> when (event) {
                is JourneyEvent.Abandoned -> {
                    val declined = state.declined + event.tool.toolId
                    // Giving up here cannot mean "finish anyway": the floor is still unmet, and
                    // finishing would put the channel in AUTHENTICATED below its own level.
                    if ((state.offered.toSet() - declined).isEmpty()) Decision.Cancel
                    else Decision.Advance(state.copy(declined = declined, active = null))
                }
                else -> settleOrRaise(ctx)
            }

            // The client answers this via JourneyService.answer; the journey ends here either way,
            // but only ACCEPT asks the machine to actually link the device.
            is LookupLoginState.OfferBinding -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    ACCEPT -> Decision.FinishWithDeviceLink(state.accountId)
                    DECLINE -> Decision.Finish
                    else -> error("OfferBinding does not understand answer '${event.answer}'")
                }
                else -> error("OfferBinding only accepts JourneyEvent.Answered")
            }
        }

    override fun onCancel(state: LookupLoginState): ChannelState = ChannelState.ANONYMOUS

    /**
     * The channel's own acrFloor applies here like it does to every other intent.
     *
     * This used to be missing outright - a proof went straight to [LookupLoginState.OfferBinding]
     * and the journey finished, so a channel opened with `requiredAcr: "loa3"` reached
     * AUTHENTICATED on one loa1 factor. `JourneyService.finish` sets AUTHENTICATED
     * unconditionally; the floor is only ever enforced by the strategy, which makes forgetting it
     * here silent. Compare `FastAccessStrategy.afterProof` and `StepUpStrategy.finishOrContinue`,
     * which do the same thing for their own intents.
     *
     * Unlike those two there is no ENROLLMENT fallback: this intent exists to log an EXISTING
     * account in from an unpaired device, so an account that simply cannot reach the floor with
     * what it already has must not grow new credentials on an unproven device. Re-identification
     * is different and stays available: it adds no lasting credential, only re-confirms the same
     * account at a higher priced-in trust level (`RE_IDENTIFY`'s `ConfirmIdentity`).
     */
    private fun settleOrRaise(ctx: JourneyContext): Decision {
        val account = ctx.requireAccount()
        if (ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) {
            return Decision.Advance(LookupLoginState.OfferBinding(account.accountId))
        }
        val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
        if (candidates.isNotEmpty()) {
            return Decision.Advance(LookupLoginState.AdditionalFactor(candidates))
        }
        return if (CandidateTools.forReIdentification(ctx.acrFloor, ctx).isNotEmpty()) {
            Decision.RequireSubJourney(AuthIntent.RE_IDENTIFY, ctx.acrFloor, resumeWith = LookupLoginState.Start)
        } else {
            Decision.Abort("Gefordertes Sicherheitsniveau ist mit den vorhandenen Methoden nicht erreichbar. ${ctx.policy.unreachableReason(account, ctx.acrFloor)}")
        }
    }

    companion object {
        /** The two answers [LookupLoginState.OfferBinding] understands (see JourneyEvent.Answered). */
        const val ACCEPT = "accept"
        const val DECLINE = "decline"
    }
}
