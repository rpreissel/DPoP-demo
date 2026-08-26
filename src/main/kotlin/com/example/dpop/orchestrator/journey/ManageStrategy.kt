package com.example.dpop.orchestrator.journey

import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Component

/**
 * Add or remove authentication methods on an already authenticated channel
 * (docs/04-orchestrierung.md #3).
 *
 * The only intent without a policy goal: ONE successful enrollment ends it, regardless of the
 * level reached - the channel was already AUTHENTICATED before it started. Adding a second method
 * means starting another journey.
 *
 * Both operations first require the CURRENT session to prove loa2, following the same
 * anti-self-escalation reasoning as the enrolledUnderAcr cap: a hijacked loa1 session must not be
 * able to add or remove credentials on its own say-so. The wish itself survives that detour: the
 * journey stays parked in [ManageState.AddRequested]/[ManageState.RemoveRequested] while the
 * step-up sub-journey runs, and re-evaluating that same state afterwards both re-checks the gate
 * and carries out what was originally asked for.
 */
@Component
class ManageStrategy : IntentStrategy<ManageState> {

    override val intent = AuthIntent.MANAGE

    override fun initial(ctx: JourneyContext): ManageState = ManageState.AddRequested

    override fun interpret(state: ManageState, tool: ToolDescriptor, outcome: ToolOutcome.Completed): Interpretation =
        when (outcome) {
            // Voluntary enrollment on a channel that is already authenticated - the device is
            // already known, so binding it again is a harmless no-op that keeps a newly enrolled
            // device credential reachable next time.
            is ToolOutcome.Completed.Enrolled -> Interpretation.AdoptCredential(bindDevice = true)
            is ToolOutcome.Completed.Identified,
            is ToolOutcome.Completed.Authenticated ->
                error("${tool.toolId} is not offered by MANAGE")
        }

    override fun next(state: ManageState, event: JourneyEvent, ctx: JourneyContext): Decision =
        when (state) {
            is ManageState.AddRequested -> gate(state, ctx) ?: enrollStage(ctx)
            is ManageState.RemoveRequested -> gate(state, ctx) ?: Decision.Remove(state.methodInstanceId)

            is ManageState.Enrolling -> when (event) {
                // Backing out here means picking a different method, not abandoning the wish -
                // the full choice comes back. Giving up entirely is DELETE .../process.
                is JourneyEvent.Abandoned -> Decision.Advance(state.copy(active = null))
                else -> Decision.Finish
            }
        }

    override fun onCancel(state: ManageState): ChannelState = ChannelState.AUTHENTICATED

    /** Null once the session already carries loa2 and the caller may proceed. */
    private fun gate(requested: ManageState, ctx: JourneyContext): Decision? {
        val account = ctx.requireAccount()
        if (ctx.policy.isSatisfied(ctx.evidence, REQUIRED_ACR, account)) return null
        return Decision.RequireSubJourney(AuthIntent.STEP_UP, REQUIRED_ACR, resumeWith = requested)
    }

    private fun enrollStage(ctx: JourneyContext): Decision {
        val candidates = CandidateTools.forEnrollment(ctx.requireAccount(), ctx.acrFloor, ctx)
        return if (candidates.isEmpty()) {
            // Not an error, just nothing left to add (docs/07-betrieb.md #1: HTTP errors are for
            // disrupted flows, not an expectable "you already have everything").
            Decision.Finish
        } else {
            Decision.Advance(ManageState.Enrolling(candidates))
        }
    }

    companion object {
        const val REQUIRED_ACR = "loa2"
    }
}
