package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.toEnrollAbortMessage
import com.example.dpop.orchestrator.journey.state.AuthChoice
import com.example.dpop.orchestrator.journey.state.Enrolling
import com.example.dpop.orchestrator.journey.state.JourneyState
import com.example.dpop.orchestrator.journey.state.ReIdentifyState
import com.example.dpop.orchestrator.journey.state.RegisterState
import com.example.dpop.orchestrator.policy.Reachability
import com.example.dpop.orchestrator.session.AcrLevel
import com.example.dpop.orchestrator.session.AcrLevels
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
internal object AuthEnrollCore {

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
        val reachable = ctx.policy.reachability(account, ctx.acrFloor) is Reachability.Reachable
        if (!reachable || !ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) {
            return offerEnrollment(account, ctx, emailObligation, resumeAtStart)
        }
        if (emailObligation && !account.emailConfirmed) {
            CandidateTools.forEmailConfirmation(ctx).takeIf { it.isNotEmpty() }
                ?.let { return Transition.To(RegisterState.ConfirmingEmail(it)) }
        }
        return Transition.Authenticated
    }

    /**
     * Never offers a NEW auth method below [ENROLLMENT_FLOOR_ACR]: a credential's `enrolledUnderAcr`
     * (ADR-5, [DefaultAuthPolicy]) permanently bakes in whatever this session had already proven at
     * the moment it was enrolled - regardless of the tool's own declared `maxAcr`. A session that
     * merely recognized the device and proved one loa1 factor (`FAST_ACCESS`'s own, identification-
     * free path, [FastAccessStrategy.firstOffer]) could otherwise enroll e.g. `enroll-device`
     * (`maxAcr=loa2`) capped at loa1 forever, with nothing surfaced until a LATER step-up
     * (`STEP_UP`/`CONFIRM_PEER_LOGIN`) confusingly rejects it as "insufficient" - a real bug report.
     * Fixed at loa2 rather than [JourneyContext.acrFloor]: this is about never handing out
     * unverified trust, not about this particular channel's own target.
     *
     * Bypassing straight to a fresh identification (never straight to failure) mirrors
     * [StepUpStrategy]'s own re-ident fallback - the one channel-independent way up from here, since
     * there is no ENROLL path that itself proves identity. Not applied to [RegisterEnrollFirstStrategy]:
     * that experiment's entire premise is enrolling BEFORE any identification exists yet (its own
     * class doc) - its capped-at-loa1 result there is the intended trade-off, not this same bug.
     */
    fun offerEnrollment(account: AccountProfile, ctx: JourneyContext, emailObligation: Boolean, resumeAtStart: JourneyState): Transition {
        if (!ctx.policy.isSatisfied(ctx.evidence, ENROLLMENT_FLOOR_ACR, account)) {
            val reidentTarget = AcrLevels.max(ENROLLMENT_FLOOR_ACR, ctx.acrFloor)
            return if (CandidateTools.forReIdentification(reidentTarget, ctx).isNotEmpty()) {
                Transition.RequireSubJourney(
                    AuthIntent.RE_IDENTIFY,
                    seedWith = ReIdentifyState.forSubJourney(reidentTarget, ctx.currentAcr),
                    resumeWith = resumeAtStart
                )
            } else {
                Transition.Abort(
                    "Fuer die Einrichtung eines neuen Anmeldeverfahrens ist eine frische Identifizierung " +
                        "(mindestens loa2) erforderlich, aktuell ist aber keine Identifizierungsmethode verfuegbar."
                )
            }
        }

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
            // Reachability.NotReachable: nothing left to enroll AND what's already active
            // genuinely can't reach the floor - that reason's own explanation applies. Reachable:
            // forEnrollment came back empty anyway for a channel-local reason (e.g. availableTools
            // disabled every remaining candidate), not an account-wide one - see
            // Reachability.toEnrollAbortMessage's own doc for the same bug in the AUTH-candidate case.
            Transition.Abort(ctx.policy.reachability(account, ctx.acrFloor).toEnrollAbortMessage())
        }
    }

    private val ENROLLMENT_FLOOR_ACR = AcrLevel("loa2")

    /**
     * On a mandatory state, backing out of a tool is not declining it - the obligation
     * stands either way. So the FULL choice comes back, including the tool just abandoned: the
     * user is picking differently, not giving up. Only fallback states accumulate `declined`.
     */
    fun reoffer(state: JourneyState): Transition = Transition.To(state.withActive(null))
}
