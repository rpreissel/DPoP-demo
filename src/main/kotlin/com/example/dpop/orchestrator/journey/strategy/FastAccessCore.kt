package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.AuthChoice
import com.example.dpop.orchestrator.journey.state.Enrolling
import com.example.dpop.orchestrator.journey.state.JourneyState
import com.example.dpop.orchestrator.journey.state.ReIdentifyState
import com.example.dpop.orchestrator.journey.state.RegisterState
import com.example.dpop.tool_spi.ToolOutcome

/**
 * The reasoning [FastAccessStrategy] and [RegisterStrategy] both need for their shared states
 * ([AuthChoice], [Enrolling]) - a plain, stateless helper (same shape as [CandidateTools]), never a
 * common base class: each strategy still owns its own `transition` function and calls in here
 * explicitly, so which states an intent can produce stays fully readable from its own file.
 *
 * [resumeAtStart] exists only because [offerEnrollment] can bottom out in a RE_IDENTIFY
 * sub-journey (docs/04-orchestrierung.md, "RE_IDENTIFY") - the caller's own `Start`-like state to
 * resume at once that finishes, so a caller never has to know the OTHER caller's resume state.
 */
internal object FastAccessCore {

    /** State-independent: the same outcome always means the same thing here (unlike e.g. RE_IDENTIFY's ConfirmIdentity). */
    fun proofAction(event: JourneyEvent.Completed): Action = when (val outcome = event.outcome) {
        // The account may be brand new or an existing one found again by KVNR; both are the
        // same decision here, which is why registration needs no state of its own.
        is ToolOutcome.Completed.Identified -> Action.AdoptIdentity(event.tool, outcome)
        // A real credential now exists on this device, so recognizing the device costs
        // nothing and saves the next login: bind it.
        is ToolOutcome.Completed.Enrolled -> Action.AdoptCredential(event.tool, outcome, bindDevice = true)
        // A device-bound tool never resolves the account itself - it could only have been
        // offered once the account was already known.
        is ToolOutcome.Completed.Authenticated -> Action.AcceptProof(event.tool, outcome, useOutcomeAccount = false, bindDevice = true)
        is ToolOutcome.Completed.Approved -> error("${event.tool.toolId} is not offered by FAST_ACCESS/REGISTER")
    }

    /** After a proof: done, another factor via [AuthChoice], or - if nothing else can help - [offerEnrollment]. */
    fun afterProof(ctx: JourneyContext, resumeAtStart: JourneyState): Transition {
        val account = ctx.requireAccount()
        if (ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) return Transition.Authenticated

        val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
        if (candidates.isNotEmpty()) return Transition.To(AuthChoice(candidates))
        // No email obligation on this path: an existing account that merely logs in is never
        // retroactively blocked on a missing confirmed email (docs/04-orchestrierung.md #8).
        return offerEnrollment(account, ctx, emailObligation = false, resumeAtStart)
    }

    /**
     * The order of the mandatory states: a sufficient login method FIRST, the confirmed email
     * after it. Reversing them would force one particular method before the user has chosen any,
     * even though setting up email is one of the choices that satisfies both at once.
     *
     * [RegisterState.ConfirmingEmail] only ever actually gets produced here when [emailObligation]
     * is true - which only [RegisterStrategy] ever passes, so this branch is simply dead code when
     * called from [FastAccessStrategy]'s own, always-`false` call sites.
     */
    fun afterEnrollment(ctx: JourneyContext, emailObligation: Boolean, resumeAtStart: JourneyState): Transition {
        val account = ctx.requireAccount()
        val reachable = ctx.policy.canAccountReach(account, ctx.acrFloor)
        if (!reachable || !ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) {
            return offerEnrollment(account, ctx, emailObligation, resumeAtStart)
        }
        if (emailObligation && !account.emailConfirmed) {
            CandidateTools.forEmailConfirmation(ctx).takeIf { it.isNotEmpty() }
                ?.let { return Transition.To(RegisterState.ConfirmingEmail(it)) }
        }
        return Transition.Authenticated
    }

    fun offerEnrollment(account: AccountProfile, ctx: JourneyContext, emailObligation: Boolean, resumeAtStart: JourneyState): Transition {
        val candidates = CandidateTools.forEnrollment(account, ctx.acrFloor, ctx)
        if (candidates.isNotEmpty()) {
            return Transition.To(Enrolling(candidates, emailObligation = emailObligation))
        }
        return if (CandidateTools.forReIdentification(ctx.acrFloor, ctx).isNotEmpty()) {
            Transition.RequireSubJourney(
                AuthIntent.RE_IDENTIFY,
                seedWith = ReIdentifyState.forSubJourney(ctx.acrFloor, ctx.currentAcr),
                resumeWith = resumeAtStart
            )
        } else {
            Transition.Abort("Gefordertes Sicherheitsniveau ist mit den vorhandenen Methoden nicht erreichbar. ${ctx.policy.unreachableReason(account, ctx.acrFloor)}")
        }
    }

    /**
     * On a mandatory state, backing out of a tool is not declining it - the obligation
     * stands either way. So the FULL choice comes back, including the tool just abandoned: the
     * user is picking differently, not giving up. Only fallback states accumulate `declined`.
     */
    fun reoffer(state: JourneyState): Transition = Transition.To(state.withActive(null))
}
