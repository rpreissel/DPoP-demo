package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.IntentStrategy
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.AuthChoice
import com.example.dpop.orchestrator.journey.state.Enrolling
import com.example.dpop.orchestrator.journey.state.FastAccessState
import com.example.dpop.orchestrator.journey.state.RegisterState
import com.example.dpop.orchestrator.session.ChannelState
import org.springframework.stereotype.Component

/**
 * Into a login on this device as fast as possible - and in a way that works again next time
 * (docs/04-orchestrierung.md #3). See [FastAccessState]'s own doc for the shape of this journey and
 * why [AuthChoice]/[Enrolling] are shared with [RegisterState] rather than owned here.
 */
@Component
class FastAccessStrategy : IntentStrategy<FastAccessState> {

    override val intent: AuthIntent = AuthIntent.FAST_ACCESS

    override fun initialState(ctx: JourneyContext): FastAccessState = FastAccessState.Start

    override fun transition(state: FastAccessState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is FastAccessState.Start -> when (event) {
                // Re-check whether the fresh proof already closes the gap before offering again -
                // fires equally whether it was RE_IDENTIFY or the REGISTER sub-journey that just
                // finished (see firstOffer/afterAuthDeclined below), the re-check is the same either way.
                is JourneyEvent.SubJourneyFinished -> AuthEnrollCore.afterProof(ctx, resumeAtStart = FastAccessState.Start)
                // No new evidence - re-deriving would just re-request the same sub-journey again.
                is JourneyEvent.SubJourneyCancelled -> Transition.Cancel
                else -> firstOffer(ctx)
            }
            is FastAccessState.PreferredAuth -> when (event) {
                is JourneyEvent.Abandoned -> afterAuthDeclined(ctx, alreadyDeclined = setOf(state.toolId))
                is JourneyEvent.Completed -> Transition.Perform(AuthEnrollCore.proofAction(event), resumeState = state)
                else -> AuthEnrollCore.afterProof(ctx, resumeAtStart = FastAccessState.Start)
            }

            is AuthChoice -> when (event) {
                is JourneyEvent.Abandoned -> {
                    val declined = state.declined + event.tool.toolId
                    val remaining = state.copy(declined = declined, active = null)
                    if (remaining.exhausted(ctx.availableTools)) afterAuthDeclined(ctx, declined) else Transition.To(remaining)
                }
                is JourneyEvent.Completed -> Transition.Perform(AuthEnrollCore.proofAction(event), resumeState = state)
                else -> AuthEnrollCore.afterProof(ctx, resumeAtStart = FastAccessState.Start)
            }

            is Enrolling -> when (event) {
                is JourneyEvent.Abandoned -> AuthEnrollCore.reoffer(state)
                is JourneyEvent.Completed -> Transition.Perform(AuthEnrollCore.proofAction(event), resumeState = state)
                // FAST_ACCESS never sets emailObligation, so this can only ever finish or re-offer
                // enrollment - never RegisterState.ConfirmingEmail (see AuthEnrollCore.afterEnrollment).
                else -> AuthEnrollCore.afterEnrollment(ctx, state.emailObligation, resumeAtStart = FastAccessState.Start)
            }
        }

    override fun cancelledTo(state: FastAccessState): ChannelState = ChannelState.ANONYMOUS

    // Offers -------------------------------------------------------------------

    private fun firstOffer(ctx: JourneyContext): Transition {
        val account = ctx.account
        if (account != null) {
            CandidateTools.preferredDeviceAuth(account, ctx)?.let { return Transition.To(FastAccessState.PreferredAuth(it)) }
            val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
            if (candidates.isNotEmpty()) return Transition.To(AuthChoice(candidates))
        }
        return requireRegister()
    }

    /** Nothing (or nothing else) provable is left on this device: hand off to a fresh identification. */
    private fun afterAuthDeclined(ctx: JourneyContext, alreadyDeclined: Set<String>): Transition {
        val account = ctx.account
        if (account != null) {
            val remaining = CandidateTools.forAuth(account, ctx.acrFloor, ctx) - alreadyDeclined
            if (remaining.isNotEmpty()) {
                return Transition.To(AuthChoice(remaining, declined = emptySet()))
            }
        }
        return requireRegister()
    }

    /**
     * FAST_ACCESS itself never identifies anyone - that's REGISTER's job alone (docs/04-
     * orchestrierung.md #2/#3), run here as a precondition, same idiom as the RE_IDENTIFY
     * sub-journey. Resuming at [FastAccessState.Start] re-checks satisfaction via [AuthEnrollCore.
     * afterProof] rather than blindly re-running [firstOffer] - a rediscovered, already-equipped
     * account is then simply already satisfied by the time control comes back.
     */
    private fun requireRegister(): Transition = Transition.RequireSubJourney(
        AuthIntent.REGISTER,
        seedWith = RegisterState.forSubJourney(),
        resumeWith = FastAccessState.Start
    )
}
