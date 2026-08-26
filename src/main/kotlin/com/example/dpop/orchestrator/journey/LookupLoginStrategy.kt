package com.example.dpop.orchestrator.journey

import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Component

/**
 * Log into an existing account without a paired device (docs/04-orchestrierung.md #3): the user
 * names an identifier and proves a credential.
 *
 * Two properties are structural here, not enforced by extra checks:
 * - There is no identification state at all: [LookupState] has none that could offer one. A
 *   check that could be forgotten is replaced by a state that cannot be reached.
 * - The device link is never a side effect. It arises only from [LookupState.OfferBinding], after
 *   the user agrees - this intent is chosen precisely by people who want no device binding.
 */
@Component
class LookupLoginStrategy : IntentStrategy<LookupState> {

    override val intent = AuthIntent.LOGIN_LOOKUP

    override fun initial(ctx: JourneyContext): LookupState = LookupState.Start

    override fun interpret(state: LookupState, tool: ToolDescriptor, outcome: ToolOutcome.Completed): Interpretation =
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

    override fun next(state: LookupState, event: JourneyEvent, ctx: JourneyContext): Decision =
        when (state) {
            is LookupState.Start -> {
                // The offered set IS "every tool that can resolve the account itself" - derived
                // from the catalog, never listed. AuthPolicy.candidateTools cannot be used: it
                // needs a resolved account, which by definition does not exist yet.
                val tools = CandidateTools.forLookupLogin(ctx)
                if (tools.isEmpty()) Decision.Abort("Kein Login-Verfahren ohne Geraetebindung verfuegbar")
                else Decision.Advance(LookupState.Credential(tools))
            }

            is LookupState.Credential -> when (event) {
                is JourneyEvent.Abandoned -> {
                    val declined = state.declined + event.tool.toolId
                    if ((state.offered.toSet() - declined).isEmpty()) Decision.Cancel
                    else Decision.Advance(state.copy(declined = declined, active = null))
                }
                else -> Decision.Advance(LookupState.OfferBinding(ctx.requireAccount().accountId))
            }

            // The client answers this by calling the binding endpoint or by finishing; either way
            // the journey ends here. See JourneyService.answerBinding.
            is LookupState.OfferBinding -> Decision.Finish
        }

    override fun onCancel(state: LookupState): ChannelState = ChannelState.ANONYMOUS
}
