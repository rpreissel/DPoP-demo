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
 *   check that could be forgotten is replaced by a state that cannot be reached.
 * - The device link is never a side effect. It arises only from [LookupLoginState.OfferBinding], after
 *   the user agrees - this intent is chosen precisely by people who want no device binding.
 */
@Component
class LookupLoginStrategy : IntentStrategy<LookupLoginState> {

    override val intent = AuthIntent.LOOKUP_LOGIN

    override fun initialState(ctx: JourneyContext): LookupLoginState = LookupLoginState.Start

    override fun interpret(state: LookupLoginState, tool: ToolDescriptor, outcome: ToolOutcome.Completed): Interpretation =
        when (outcome) {
            // The only intent that trusts a tool to resolve the account itself - everywhere else
            // the account must already be known from the channel or the journey.
            is ToolOutcome.Completed.Authenticated ->
                Interpretation.AcceptProof(useOutcomeAccount = true, bindDevice = false)
            // Neither can be offered by any state of this intent; reaching here would mean the
            // state machine let through a tool it never offered.
            is ToolOutcome.Completed.Identified,
            is ToolOutcome.Completed.Enrolled ->
                error("${tool.toolId} is not offered by LOGIN_LOOKUP")
        }

    override fun next(state: LookupLoginState, event: JourneyEvent, ctx: JourneyContext): Decision =
        when (state) {
            is LookupLoginState.Start -> {
                // The offered set IS "every tool that can resolve the account itself" - derived
                // from the catalog, never listed. AuthPolicy.candidateTools cannot be used: it
                // needs a resolved account, which by definition does not exist yet.
                val tools = CandidateTools.forLookupLogin(ctx)
                if (tools.isEmpty()) Decision.Abort("Kein Login-Verfahren ohne Geraetebindung verfuegbar")
                else Decision.Advance(LookupLoginState.Credential(tools))
            }

            is LookupLoginState.Credential -> when (event) {
                is JourneyEvent.Abandoned -> {
                    val declined = state.declined + event.tool.toolId
                    if ((state.offered.toSet() - declined).isEmpty()) Decision.Cancel
                    else Decision.Advance(state.copy(declined = declined, active = null))
                }
                else -> Decision.Advance(LookupLoginState.OfferBinding(ctx.requireAccount().accountId))
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

    companion object {
        /** The two answers [LookupLoginState.OfferBinding] understands (see JourneyEvent.Answered). */
        const val ACCEPT = "accept"
        const val DECLINE = "decline"
    }
}
