package com.example.dpop.orchestrator.journey

import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Component

/**
 * Into a login on this device as fast as possible - and in a way that works again next time
 * (docs/04-orchestrierung.md #3).
 *
 * Stages 1-3 ([FastState.PreferredAuth], [FastState.AuthChoice], [FastState.Identifying]) are a
 * FALLBACK ladder: declining moves on. Stages 4-5 ([FastState.ConfirmingEmail],
 * [FastState.Enrolling]) are GOAL-DRIVEN: only fulfilling moves on.
 *
 * Registration is not a separate intent, it is stage 3 of this one. Whether identifying created an
 * account or found an existing one is decided afterwards by `findOrCreateAccount` - an
 * observation about the path taken, never a goal chosen up front.
 */
@Component
open class FastStrategy : IntentStrategy<FastState> {

    override val intent: AuthIntent = AuthIntent.FAST

    override fun initial(ctx: JourneyContext): FastState = FastState.Start

    override fun interpret(state: FastState, tool: ToolDescriptor, outcome: ToolOutcome.Completed): Interpretation =
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

    override fun next(state: FastState, event: JourneyEvent, ctx: JourneyContext): Decision =
        when (state) {
            is FastState.Start -> firstStage(ctx)
            is FastState.PreferredAuth -> when (event) {
                is JourneyEvent.Abandoned -> afterAuthDeclined(ctx, alreadyDeclined = setOf(state.toolId))
                else -> afterProof(ctx)
            }

            is FastState.AuthChoice -> when (event) {
                is JourneyEvent.Abandoned -> {
                    val declined = state.declined + event.tool.toolId
                    val remaining = state.copy(declined = declined, active = null)
                    if (remaining.exhausted) afterAuthDeclined(ctx, declined) else Decision.Advance(remaining)
                }
                else -> afterProof(ctx)
            }

            is FastState.Identifying -> when (event) {
                is JourneyEvent.Abandoned -> declineOnLastStage(state, event)
                // Deliberately never checks isSatisfied: identification evidence alone (amr=fsc)
                // trivially clears most floors, which would let a run finish without a single
                // durable credential ever being proven or created.
                else -> afterIdentification(ctx)
            }

            is FastState.ConfirmingEmail -> when (event) {
                is JourneyEvent.Abandoned -> reoffer(state)
                // The obligation is discharged by getting here successfully, so the re-check
                // below can only ever send the run on to enrollment or to the end.
                else -> afterEnrollment(ctx, emailObligation = false)
            }

            is FastState.Enrolling -> when (event) {
                is JourneyEvent.Abandoned -> reoffer(state)
                else -> afterEnrollment(ctx, state.emailObligation)
            }
        }

    override fun onCancel(state: FastState): ChannelState = ChannelState.ANONYMOUS

    // Stages ------------------------------------------------------------------

    /**
     * Stage 1 of the ladder. REGISTER overrides exactly this and nothing else: it is FAST minus
     * the shortcuts, entering at the identification stage.
     */
    protected open fun firstStage(ctx: JourneyContext): Decision {
        val account = ctx.account
        if (account != null) {
            CandidateTools.preferredDeviceAuth(account, ctx)?.let { return Decision.Advance(FastState.PreferredAuth(it)) }
            val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
            if (candidates.isNotEmpty()) return Decision.Advance(FastState.AuthChoice(candidates))
        }
        return identifyStage(ctx)
    }

    /** Nothing (or nothing else) provable is left: fall through to the identification stage. */
    private fun afterAuthDeclined(ctx: JourneyContext, alreadyDeclined: Set<String>): Decision {
        val account = ctx.account
        if (account != null) {
            val remaining = CandidateTools.forAuth(account, ctx.acrFloor, ctx) - alreadyDeclined
            if (remaining.isNotEmpty()) {
                return Decision.Advance(FastState.AuthChoice(remaining, declined = emptySet()))
            }
        }
        return identifyStage(ctx)
    }

    protected fun identifyStage(ctx: JourneyContext): Decision {
        val idents = CandidateTools.forIdentification(ctx)
        return if (idents.isEmpty()) {
            Decision.Abort("Kein Identifizierungsverfahren verfuegbar")
        } else {
            Decision.Advance(FastState.Identifying(idents))
        }
    }

    /** After a proof on stage 1 or 2: done, another factor, or - if the account can't reach the floor - enrollment. */
    private fun afterProof(ctx: JourneyContext): Decision {
        val account = ctx.requireAccount()
        if (ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) return Decision.Finish

        val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
        if (candidates.isNotEmpty()) return Decision.Advance(FastState.AuthChoice(candidates))
        // No email obligation on this path: an existing account that merely logs in is never
        // retroactively blocked on a missing confirmed email (docs/04-orchestrierung.md #8).
        return enrollStage(account, ctx, emailObligation = false)
    }

    private fun afterIdentification(ctx: JourneyContext): Decision {
        val account = ctx.requireAccount()
        // An account found again by KVNR may already have everything it needs - offering an
        // existing method to prove beats an enrollment list that would come back empty.
        if (ctx.policy.canAccountReach(account, ctx.acrFloor)) {
            val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
            if (candidates.isNotEmpty()) return Decision.Advance(FastState.AuthChoice(candidates))
        }
        return enrollStage(account, ctx, emailObligation = true)
    }

    /**
     * The order of the goal-driven stages: a sufficient login method FIRST, the confirmed email
     * after it. Reversing them would force one particular method before the user has chosen any,
     * even though setting up email is one of the choices that satisfies both at once.
     */
    private fun afterEnrollment(ctx: JourneyContext, emailObligation: Boolean): Decision {
        val account = ctx.requireAccount()
        val reachable = ctx.policy.canAccountReach(account, ctx.acrFloor)
        if (!reachable || !ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) {
            return enrollStage(account, ctx, emailObligation)
        }
        if (emailObligation && !account.emailConfirmed) {
            CandidateTools.forEmailConfirmation(ctx).takeIf { it.isNotEmpty() }
                ?.let { return Decision.Advance(FastState.ConfirmingEmail(it)) }
        }
        return Decision.Finish
    }

    private fun enrollStage(account: AccountProfile, ctx: JourneyContext, emailObligation: Boolean): Decision {
        val candidates = CandidateTools.forEnrollment(account, ctx.acrFloor, ctx)
        return if (candidates.isEmpty()) {
            Decision.Abort("Gefordertes Sicherheitsniveau ist mit den vorhandenen Methoden nicht erreichbar")
        } else {
            Decision.Advance(FastState.Enrolling(candidates, emailObligation = emailObligation))
        }
    }

    /** Abandoning the last fallback stage is giving up on the journey, not an error. */
    private fun declineOnLastStage(state: OfferingState, event: JourneyEvent.Abandoned): Decision {
        val declined = state.declined + event.tool.toolId
        val remaining = state.offered.toSet() - declined
        return if (remaining.isEmpty()) {
            Decision.Cancel
        } else {
            Decision.Advance(FastState.Identifying(state.offered, declined))
        }
    }

    /**
     * On a goal-driven stage, backing out of a tool is not declining the stage - the obligation
     * stands either way. So the FULL choice comes back, including the tool just abandoned: the
     * user is picking differently, not giving up. Only fallback stages accumulate `declined`.
     */
    private fun reoffer(state: FastState): Decision = Decision.Advance(state.withActive(null))
}
