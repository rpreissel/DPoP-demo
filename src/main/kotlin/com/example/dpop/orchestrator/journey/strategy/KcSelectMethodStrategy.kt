package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.Decision
import com.example.dpop.orchestrator.journey.Effect
import com.example.dpop.orchestrator.journey.IntentStrategy
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.state.KcSelectMethodState
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Component

/**
 * The Web-Kanal entry intent (docs/ideen/web-keycloak-kanal.md #7): a single `selectMethod`
 * step listing every kc-usable tool, unconditionally - no fallback chain, no enrollment offer,
 * no sufficiency check before offering. Keycloak's own flow configuration decides which
 * execution runs where and whether the reached level is enough; this strategy only ever answers
 * "what could still prove something here".
 *
 * Serves both Web-Kanal cases from the same state shape:
 * - **Initial login** ([JourneyContext.account] is `null`): resolves the account itself via
 *   lookup-login tools only ([CandidateTools.forLookupLogin]) - never identification
 *   ([CandidateTools.forIdentification]), which is an App-Kanal-only concept with no Keycloak
 *   equivalent, and never an enrollment.
 * - **Step-up** (account pre-set on the channel before this journey starts, docs/ideen/
 *   web-keycloak-kanal.md #6): only auth tools for that already-known account, like
 *   `FAST_ACCESS` treats a recognized-but-unproven device.
 */
@Component
class KcSelectMethodStrategy : IntentStrategy<KcSelectMethodState> {

    override val intent = AuthIntent.KC_SELECT_METHOD

    override fun initialState(ctx: JourneyContext): KcSelectMethodState =
        KcSelectMethodState.SelectMethod(candidatesFor(ctx), accountAlreadyKnown = ctx.account != null)

    override fun interpret(state: KcSelectMethodState, tool: ToolDescriptor, outcome: ToolOutcome.Completed): Effect =
        when (outcome) {
            // A lookup-login tool resolves its own account on the FIRST proof only (no account
            // known yet); once the channel already has one (step-up), a proof must confirm THAT
            // account, never name a different one - same rule as LookupLoginStrategy.
            is ToolOutcome.Completed.Authenticated -> Effect.AcceptProof(
                useOutcomeAccount = !state.accountAlreadyKnown,
                bindDevice = false
            )
            // Neither identification nor enrollment is ever offered here (see candidatesFor) -
            // reaching either would mean the state machine let through a tool it never offered.
            is ToolOutcome.Completed.Identified,
            is ToolOutcome.Completed.Enrolled -> error("${tool.toolId} is not offered by KC_SELECT_METHOD")
        }

    override fun decide(state: KcSelectMethodState, event: JourneyEvent, ctx: JourneyContext): Decision =
        when (state) {
            is KcSelectMethodState.SelectMethod -> when (event) {
                // The state built by initialState() IS the first offer already - unlike a proof
                // outcome or a decline, Started carries nothing new to re-derive from.
                is JourneyEvent.Started -> Decision.Advance(state)
                // A simulated native authenticator (Mock-Keycloak) merged fresh evidence -
                // same re-check as after any real proof.
                is JourneyEvent.EvidenceUpdated -> afterProof(ctx)
                is JourneyEvent.Abandoned -> {
                    val declined = state.declined + event.tool.toolId
                    val remaining = state.copy(declined = declined, active = null)
                    if (remaining.exhausted(ctx.availableTools)) Decision.Cancel else Decision.Advance(remaining)
                }
                else -> afterProof(ctx)
            }
        }

    override fun cancelledTo(state: KcSelectMethodState): ChannelState = ChannelState.ANONYMOUS

    private fun afterProof(ctx: JourneyContext): Decision {
        val account = ctx.requireAccount()
        if (ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) return Decision.Authenticated
        return Decision.Advance(KcSelectMethodState.SelectMethod(candidatesFor(ctx), accountAlreadyKnown = true))
    }

    /**
     * Never [CandidateTools.forIdentification] and never an enrollment (docs/ideen/
     * web-keycloak-kanal.md #7) - the Web-Kanal only ever proves an EXISTING identity, either by
     * resolving the account itself (no account yet: [CandidateTools.forLookupLogin]) or by
     * authenticating an already-known one ([CandidateTools.forAuth]). Fresh identification is an
     * App-Kanal-only concept (`ident-fsc`/`ident-eid`); Keycloak's own login screen has no
     * equivalent step for it.
     */
    private fun candidatesFor(ctx: JourneyContext): List<String> {
        val account = ctx.account
        return if (account == null) {
            CandidateTools.forLookupLogin(ctx)
        } else {
            CandidateTools.forAuth(account, ctx.acrFloor, ctx)
        }
    }
}
