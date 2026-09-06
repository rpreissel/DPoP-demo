package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.IntentStrategy
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.KcSelectMethodState
import com.example.dpop.orchestrator.session.ChannelState
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

    override fun transition(state: KcSelectMethodState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is KcSelectMethodState.SelectMethod -> when (event) {
                // Evidence may already satisfy the floor before this very first offer - a seeded
                // RestoreData Anfangs-Übergang (docs/ideen/journey-strategie-vereinheitlichung.md
                // #3) can run before Started ever fires - so Started can no longer blindly re-show
                // `state`, it must re-check exactly like any other proof.
                is JourneyEvent.Started -> afterProof(ctx)
                is JourneyEvent.Completed -> Transition.Perform(proofAction(state, event), resumeState = state)
                is JourneyEvent.Abandoned -> {
                    val declined = state.declined + event.tool.toolId
                    val remaining = state.copy(declined = declined, active = null)
                    if (remaining.exhausted(ctx.availableTools)) Transition.Cancel else Transition.To(remaining)
                }
                // EvidenceReported (a simulated native authenticator, Mock-Keycloak, merged fresh
                // evidence) and ActionCompleted (resumed after a proof/seed just applied) both
                // re-check the same way.
                else -> afterProof(ctx)
            }
        }

    override fun cancelledTo(state: KcSelectMethodState): ChannelState = ChannelState.ANONYMOUS

    /**
     * A lookup-login tool resolves its own account on the FIRST proof only (no account known
     * yet); once the channel already has one (step-up), a proof must confirm THAT account, never
     * name a different one - same rule as [LookupLoginStrategy]. Neither identification nor
     * enrollment is ever offered here (see [candidatesFor]) - reaching either would mean the state
     * machine let through a tool it never offered.
     */
    private fun proofAction(state: KcSelectMethodState.SelectMethod, event: JourneyEvent.Completed): Action =
        when (val outcome = event.outcome) {
            is ToolOutcome.Completed.Authenticated -> Action.AcceptProof(
                event.tool, outcome, useOutcomeAccount = !state.accountAlreadyKnown, bindDevice = false
            )
            is ToolOutcome.Completed.Identified, is ToolOutcome.Completed.Enrolled ->
                error("${event.tool.toolId} is not offered by KC_SELECT_METHOD")
        }

    private fun afterProof(ctx: JourneyContext): Transition {
        val account = ctx.account
        if (account != null && ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) return Transition.Authenticated
        return Transition.To(KcSelectMethodState.SelectMethod(candidatesFor(ctx), accountAlreadyKnown = account != null))
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
