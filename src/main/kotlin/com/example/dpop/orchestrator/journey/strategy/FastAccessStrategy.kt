package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.IntentStrategy
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.FastAccessState
import com.example.dpop.orchestrator.journey.state.OfferingState
import com.example.dpop.orchestrator.session.ChannelState
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

    override fun transition(state: FastAccessState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is FastAccessState.Start -> when (event) {
                // Re-check whether the fresh proof already closes the gap before offering again.
                is JourneyEvent.SubJourneyFinished -> afterProof(ctx)
                // No new evidence - re-deriving would just re-request the same RE_IDENTIFY again.
                is JourneyEvent.SubJourneyCancelled -> Transition.Cancel
                else -> firstOffer(ctx)
            }
            is FastAccessState.PreferredAuth -> when (event) {
                is JourneyEvent.Abandoned -> afterAuthDeclined(ctx, alreadyDeclined = setOf(state.toolId))
                is JourneyEvent.Completed -> Transition.Perform(proofAction(event), resumeState = state)
                else -> afterProof(ctx)
            }

            is FastAccessState.AuthChoice -> when (event) {
                is JourneyEvent.Abandoned -> {
                    val declined = state.declined + event.tool.toolId
                    val remaining = state.copy(declined = declined, active = null)
                    if (remaining.exhausted(ctx.availableTools)) afterAuthDeclined(ctx, declined) else Transition.To(remaining)
                }
                is JourneyEvent.Completed -> Transition.Perform(proofAction(event), resumeState = state)
                else -> afterProof(ctx)
            }

            is FastAccessState.Identifying -> when (event) {
                is JourneyEvent.Abandoned -> giveUpOrReoffer(state, event)
                is JourneyEvent.Completed -> Transition.Perform(proofAction(event), resumeState = state)
                // Deliberately never checks isSatisfied: identification evidence alone (amr=fsc)
                // trivially clears most floors, which would let a run finish without a single
                // durable credential ever being proven or created.
                else -> afterIdentification(ctx)
            }

            is FastAccessState.ConfirmingEmail -> when (event) {
                is JourneyEvent.Abandoned -> reoffer(state)
                is JourneyEvent.Completed -> Transition.Perform(proofAction(event), resumeState = state)
                // The obligation is discharged by getting here successfully, so the re-check
                // below can only ever send the run on to enrollment or to the end.
                else -> afterEnrollment(ctx, emailObligation = false)
            }

            is FastAccessState.Enrolling -> when (event) {
                is JourneyEvent.Abandoned -> reoffer(state)
                is JourneyEvent.Completed -> Transition.Perform(proofAction(event), resumeState = state)
                else -> afterEnrollment(ctx, state.emailObligation)
            }
        }

    override fun cancelledTo(state: FastAccessState): ChannelState = ChannelState.ANONYMOUS

    // Interpretation -------------------------------------------------------------

    /** State-independent: the same outcome always means the same thing here (unlike e.g. RE_IDENTIFY's ConfirmIdentity). */
    private fun proofAction(event: JourneyEvent.Completed): Action = when (val outcome = event.outcome) {
        // The account may be brand new or an existing one found again by KVNR; both are the
        // same decision here, which is why registration needs no state of its own.
        is ToolOutcome.Completed.Identified -> Action.AdoptIdentity(event.tool, outcome)
        // A real credential now exists on this device, so recognizing the device costs
        // nothing and saves the next login: bind it.
        is ToolOutcome.Completed.Enrolled -> Action.AdoptCredential(event.tool, outcome, bindDevice = true)
        // A device-bound tool never resolves the account itself - it could only have been
        // offered once the account was already known.
        is ToolOutcome.Completed.Authenticated -> Action.AcceptProof(event.tool, outcome, useOutcomeAccount = false, bindDevice = true)
    }

    // Offers -------------------------------------------------------------------

    /**
     * Where the fallback chain starts. REGISTER overrides exactly this and nothing else: it is FAST minus
     * the shortcuts, entering at the identification state.
     */
    protected open fun firstOffer(ctx: JourneyContext): Transition {
        val account = ctx.account
        if (account != null) {
            CandidateTools.preferredDeviceAuth(account, ctx)?.let { return Transition.To(FastAccessState.PreferredAuth(it)) }
            val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
            if (candidates.isNotEmpty()) return Transition.To(FastAccessState.AuthChoice(candidates))
        }
        return offerIdentification(ctx)
    }

    /** Nothing (or nothing else) provable is left: fall through to the identification state. */
    private fun afterAuthDeclined(ctx: JourneyContext, alreadyDeclined: Set<String>): Transition {
        val account = ctx.account
        if (account != null) {
            val remaining = CandidateTools.forAuth(account, ctx.acrFloor, ctx) - alreadyDeclined
            if (remaining.isNotEmpty()) {
                return Transition.To(FastAccessState.AuthChoice(remaining, declined = emptySet()))
            }
        }
        return offerIdentification(ctx)
    }

    protected fun offerIdentification(ctx: JourneyContext): Transition {
        val idents = CandidateTools.forIdentification(ctx)
        return if (idents.isEmpty()) {
            Transition.Abort("Kein Identifizierungsverfahren verfuegbar")
        } else {
            Transition.To(FastAccessState.Identifying(idents))
        }
    }

    /** After a proof on the first or second state: done, another factor, or - if the account can't reach the floor - enrollment. */
    private fun afterProof(ctx: JourneyContext): Transition {
        val account = ctx.requireAccount()
        if (ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) return Transition.Authenticated

        val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
        if (candidates.isNotEmpty()) return Transition.To(FastAccessState.AuthChoice(candidates))
        // No email obligation on this path: an existing account that merely logs in is never
        // retroactively blocked on a missing confirmed email (docs/04-orchestrierung.md #8).
        return offerEnrollment(account, ctx, emailObligation = false)
    }

    private fun afterIdentification(ctx: JourneyContext): Transition {
        val account = ctx.requireAccount()
        // An account found again by KVNR may already have everything it needs - offering an
        // existing method to prove beats an enrollment list that would come back empty.
        if (ctx.policy.canAccountReach(account, ctx.acrFloor)) {
            val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
            if (candidates.isNotEmpty()) return Transition.To(FastAccessState.AuthChoice(candidates))
        }
        return offerEnrollment(account, ctx, emailObligation = true)
    }

    /**
     * The order of the mandatory states: a sufficient login method FIRST, the confirmed email
     * after it. Reversing them would force one particular method before the user has chosen any,
     * even though setting up email is one of the choices that satisfies both at once.
     */
    private fun afterEnrollment(ctx: JourneyContext, emailObligation: Boolean): Transition {
        val account = ctx.requireAccount()
        val reachable = ctx.policy.canAccountReach(account, ctx.acrFloor)
        if (!reachable || !ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) {
            return offerEnrollment(account, ctx, emailObligation)
        }
        if (emailObligation && !account.emailConfirmed) {
            CandidateTools.forEmailConfirmation(ctx).takeIf { it.isNotEmpty() }
                ?.let { return Transition.To(FastAccessState.ConfirmingEmail(it)) }
        }
        return Transition.Authenticated
    }

    private fun offerEnrollment(account: AccountProfile, ctx: JourneyContext, emailObligation: Boolean): Transition {
        val candidates = CandidateTools.forEnrollment(account, ctx.acrFloor, ctx)
        if (candidates.isNotEmpty()) {
            return Transition.To(FastAccessState.Enrolling(candidates, emailObligation = emailObligation))
        }
        return if (CandidateTools.forReIdentification(ctx.acrFloor, ctx).isNotEmpty()) {
            Transition.RequireSubJourney(AuthIntent.RE_IDENTIFY, ctx.acrFloor, resumeWith = FastAccessState.Start)
        } else {
            Transition.Abort("Gefordertes Sicherheitsniveau ist mit den vorhandenen Methoden nicht erreichbar. ${ctx.policy.unreachableReason(account, ctx.acrFloor)}")
        }
    }

    /** Abandoning the last fallback state is giving up on the journey, not an error. */
    private fun giveUpOrReoffer(state: OfferingState, event: JourneyEvent.Abandoned): Transition {
        val declined = state.declined + event.tool.toolId
        val remaining = state.offered.toSet() - declined
        return if (remaining.isEmpty()) {
            Transition.Cancel
        } else {
            Transition.To(FastAccessState.Identifying(state.offered, declined))
        }
    }

    /**
     * On a mandatory state, backing out of a tool is not declining it - the obligation
     * stands either way. So the FULL choice comes back, including the tool just abandoned: the
     * user is picking differently, not giving up. Only fallback states accumulate `declined`.
     */
    private fun reoffer(state: FastAccessState): Transition = Transition.To(state.withActive(null))
}
