package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.Decision
import com.example.dpop.orchestrator.journey.state.FastAccessState
import com.example.dpop.orchestrator.journey.IntentStrategy
import com.example.dpop.orchestrator.journey.Interpretation
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.state.OfferingState
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Component

/**
 * Into a login on this device as fast as possible - and in a way that works again next time
 * (docs/04-orchestrierung.md #3).
 *
 * A successful proof moves every state on alike; what separates them is what DECLINING does.
 * States 1-3 ([FastAccessState.PreferredAuth], [FastAccessState.AuthChoice], [FastAccessState.Identifying]) form a
 * FALLBACK chain: declining moves to the next, more laborious way in, and once nothing is left
 * the journey ends. States 4-5 ([FastAccessState.ConfirmingEmail], [FastAccessState.Enrolling]) are
 * MANDATORY: declining re-offers the same full choice, so only fulfilling moves on.
 *
 * Registration is not a separate intent, it is the third state of this one. Whether identifying
 * created an account or found an existing one is decided afterwards by `findOrCreateAccount` - an
 * observation about the path taken, never a goal chosen up front.
 */
@Component
open class FastAccessStrategy : IntentStrategy<FastAccessState> {

    override val intent: AuthIntent = AuthIntent.FAST_ACCESS

    override fun initialState(ctx: JourneyContext): FastAccessState = FastAccessState.Start

    override fun interpret(state: FastAccessState, tool: ToolDescriptor, outcome: ToolOutcome.Completed): Interpretation =
        when (outcome) {
            // The account may be brand new or an existing one found again by KVNR; both are the
            // same decision here, which is why registration needs no state of its own.
            is ToolOutcome.Completed.Identified -> Interpretation.AdoptIdentity
            // A real credential now exists on this device, so recognizing the device costs
            // nothing and saves the next login: bind it.
            is ToolOutcome.Completed.Enrolled -> Interpretation.AdoptCredential(bindDevice = true)
            // A device-bound tool never resolves the account itself - it could only have been
            // offered once the account was already known.
            is ToolOutcome.Completed.Authenticated ->
                Interpretation.AcceptProof(useOutcomeAccount = false, bindDevice = true)
        }

    override fun next(state: FastAccessState, event: JourneyEvent, ctx: JourneyContext): Decision =
        when (state) {
            is FastAccessState.Start -> when (event) {
                // Resumed after a RE_IDENTIFY sub-journey - re-check whether the fresh proof
                // already closes the gap before trying to offer auth/enrollment again.
                is JourneyEvent.SubJourneyFinished -> afterProof(ctx)
                else -> firstOffer(ctx)
            }
            is FastAccessState.PreferredAuth -> when (event) {
                is JourneyEvent.Abandoned -> afterAuthDeclined(ctx, alreadyDeclined = setOf(state.toolId))
                else -> afterProof(ctx)
            }

            is FastAccessState.AuthChoice -> when (event) {
                is JourneyEvent.Abandoned -> {
                    val declined = state.declined + event.tool.toolId
                    val remaining = state.copy(declined = declined, active = null)
                    if (remaining.exhausted(ctx.availableTools)) afterAuthDeclined(ctx, declined) else Decision.Advance(remaining)
                }
                else -> afterProof(ctx)
            }

            is FastAccessState.Identifying -> when (event) {
                is JourneyEvent.Abandoned -> giveUpOrReoffer(state, event)
                // Deliberately never checks isSatisfied: identification evidence alone (amr=fsc)
                // trivially clears most floors, which would let a run finish without a single
                // durable credential ever being proven or created.
                else -> afterIdentification(ctx)
            }

            is FastAccessState.ConfirmingEmail -> when (event) {
                is JourneyEvent.Abandoned -> reoffer(state)
                // The obligation is discharged by getting here successfully, so the re-check
                // below can only ever send the run on to enrollment or to the end.
                else -> afterEnrollment(ctx, emailObligation = false)
            }

            is FastAccessState.Enrolling -> when (event) {
                is JourneyEvent.Abandoned -> reoffer(state)
                else -> afterEnrollment(ctx, state.emailObligation)
            }
        }

    override fun onCancel(state: FastAccessState): ChannelState = ChannelState.ANONYMOUS

    // Offers -------------------------------------------------------------------

    /**
     * Where the fallback chain starts. REGISTER overrides exactly this and nothing else: it is FAST minus
     * the shortcuts, entering at the identification state.
     */
    protected open fun firstOffer(ctx: JourneyContext): Decision {
        val account = ctx.account
        if (account != null) {
            CandidateTools.preferredDeviceAuth(account, ctx)?.let { return Decision.Advance(FastAccessState.PreferredAuth(it)) }
            val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
            if (candidates.isNotEmpty()) return Decision.Advance(FastAccessState.AuthChoice(candidates))
        }
        return offerIdentification(ctx)
    }

    /** Nothing (or nothing else) provable is left: fall through to the identification state. */
    private fun afterAuthDeclined(ctx: JourneyContext, alreadyDeclined: Set<String>): Decision {
        val account = ctx.account
        if (account != null) {
            val remaining = CandidateTools.forAuth(account, ctx.acrFloor, ctx) - alreadyDeclined
            if (remaining.isNotEmpty()) {
                return Decision.Advance(FastAccessState.AuthChoice(remaining, declined = emptySet()))
            }
        }
        return offerIdentification(ctx)
    }

    protected fun offerIdentification(ctx: JourneyContext): Decision {
        val idents = CandidateTools.forIdentification(ctx)
        return if (idents.isEmpty()) {
            Decision.Abort("Kein Identifizierungsverfahren verfuegbar")
        } else {
            Decision.Advance(FastAccessState.Identifying(idents))
        }
    }

    /** After a proof on the first or second state: done, another factor, or - if the account can't reach the floor - enrollment. */
    private fun afterProof(ctx: JourneyContext): Decision {
        val account = ctx.requireAccount()
        if (ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) return Decision.Finish

        val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
        if (candidates.isNotEmpty()) return Decision.Advance(FastAccessState.AuthChoice(candidates))
        // No email obligation on this path: an existing account that merely logs in is never
        // retroactively blocked on a missing confirmed email (docs/04-orchestrierung.md #8).
        return offerEnrollment(account, ctx, emailObligation = false)
    }

    private fun afterIdentification(ctx: JourneyContext): Decision {
        val account = ctx.requireAccount()
        // An account found again by KVNR may already have everything it needs - offering an
        // existing method to prove beats an enrollment list that would come back empty.
        if (ctx.policy.canAccountReach(account, ctx.acrFloor)) {
            val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
            if (candidates.isNotEmpty()) return Decision.Advance(FastAccessState.AuthChoice(candidates))
        }
        return offerEnrollment(account, ctx, emailObligation = true)
    }

    /**
     * The order of the mandatory states: a sufficient login method FIRST, the confirmed email
     * after it. Reversing them would force one particular method before the user has chosen any,
     * even though setting up email is one of the choices that satisfies both at once.
     */
    private fun afterEnrollment(ctx: JourneyContext, emailObligation: Boolean): Decision {
        val account = ctx.requireAccount()
        val reachable = ctx.policy.canAccountReach(account, ctx.acrFloor)
        if (!reachable || !ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) {
            return offerEnrollment(account, ctx, emailObligation)
        }
        if (emailObligation && !account.emailConfirmed) {
            CandidateTools.forEmailConfirmation(ctx).takeIf { it.isNotEmpty() }
                ?.let { return Decision.Advance(FastAccessState.ConfirmingEmail(it)) }
        }
        return Decision.Finish
    }

    private fun offerEnrollment(account: AccountProfile, ctx: JourneyContext, emailObligation: Boolean): Decision {
        val candidates = CandidateTools.forEnrollment(account, ctx.acrFloor, ctx)
        if (candidates.isNotEmpty()) {
            return Decision.Advance(FastAccessState.Enrolling(candidates, emailObligation = emailObligation))
        }
        return if (CandidateTools.forReIdentification(ctx.acrFloor, ctx).isNotEmpty()) {
            Decision.RequireSubJourney(AuthIntent.RE_IDENTIFY, ctx.acrFloor, resumeWith = FastAccessState.Start)
        } else {
            Decision.Abort("Gefordertes Sicherheitsniveau ist mit den vorhandenen Methoden nicht erreichbar. ${ctx.policy.unreachableReason(account, ctx.acrFloor)}")
        }
    }

    /** Abandoning the last fallback state is giving up on the journey, not an error. */
    private fun giveUpOrReoffer(state: OfferingState, event: JourneyEvent.Abandoned): Decision {
        val declined = state.declined + event.tool.toolId
        val remaining = state.offered.toSet() - declined
        return if (remaining.isEmpty()) {
            Decision.Cancel
        } else {
            Decision.Advance(FastAccessState.Identifying(state.offered, declined))
        }
    }

    /**
     * On a mandatory state, backing out of a tool is not declining it - the obligation
     * stands either way. So the FULL choice comes back, including the tool just abandoned: the
     * user is picking differently, not giving up. Only fallback states accumulate `declined`.
     */
    private fun reoffer(state: FastAccessState): Decision = Decision.Advance(state.withActive(null))
}
